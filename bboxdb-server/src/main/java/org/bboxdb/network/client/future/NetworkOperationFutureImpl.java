/*******************************************************************************
 *
 *    Copyright (C) 2015-2018 the BBoxDB project
 *  
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License. 
 *    
 *******************************************************************************/
package org.bboxdb.network.client.future;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bboxdb.network.client.BBoxDBConnection;
import org.bboxdb.network.packages.NetworkRequestPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

public class NetworkOperationFutureImpl implements NetworkOperationFuture {
	
	/**
	 * The id of the operation
	 */
	private short requestId;
	
	/**
	 * The result of the operation
	 */
	private volatile Object operationResult = null;
	
	/**
	 * The latch for sync operations
	 */
	private final CountDownLatch latch = new CountDownLatch(1);
	
	/**
	 * The error flag for the operation
	 */
	private volatile boolean failed = false;
	
	/**
	 * The done flag
	 */
	private volatile boolean done = false;
	
	/**
	 * The complete / partial result flag
	 */
	private volatile boolean complete = true;
	
	/**
	 * Additional message
	 */
	private String message;
	
	/**
	 * The future start time
	 */
	private final Stopwatch stopwatch;
	
	/**
	 * The associated connection
	 */
	private final BBoxDBConnection connection;
	
	/**
	 * The package supplier
	 */
	private Supplier<NetworkRequestPackage> packageSupplier;

	/**
	 * The executions
	 */
	private final AtomicInteger executions = new AtomicInteger(0);
	
	/**
	 * The last send package
	 */
	private NetworkRequestPackage lastTransmittedPackage;
	
	/**
	 * The success callback
	 */
	protected Consumer<NetworkOperationFutureImpl> successCallback;
	
	/**
	 * The error callback
	 */
	protected FutureErrorCallback errorCallback;

	/**
	 * The Logger
	 */
	protected final static Logger logger = LoggerFactory.getLogger(NetworkOperationFutureImpl.class);

	/**
	 * Empty constructor
	 */
	public NetworkOperationFutureImpl(final BBoxDBConnection connection, 
			final Supplier<NetworkRequestPackage> packageSupplier) {
		
		this.packageSupplier = packageSupplier;
		this.stopwatch = Stopwatch.createStarted();
		this.connection = connection;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#isDone()
	 */
	@Override
	public boolean isDone() {
		return done;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#execute()
	 */
	@Override
	public void execute() {		
		this.lastTransmittedPackage = packageSupplier.get();
		this.failed = false;		
		this.executions.incrementAndGet();
		
		// Can be null in some unit tests
		if(lastTransmittedPackage != null) {
			this.requestId = lastTransmittedPackage.getSequenceNumber();
		}
		
		connection.registerPackageCallback(lastTransmittedPackage, this);
		connection.sendPackageToServer(lastTransmittedPackage, this);
	}
	
	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#get()
	 */
	@Override
	public Object get() throws InterruptedException {
		latch.await();
		return operationResult;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#get(long, java.util.concurrent.TimeUnit)
	 */
	@Override
	public Object get(final long timeout, final TimeUnit unit) throws InterruptedException, TimeoutException {

		latch.await(timeout, unit);
		
		if(! done) {
			throw new TimeoutException("Unable to receive data in " + timeout + " " + unit);
		}
				
		return operationResult;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getRequestId()
	 */
	@Override
	public short getRequestId() {
		return requestId;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setOperationResult(java.lang.Object)
	 */
	@Override
	public void setOperationResult(final Object result) {
		this.operationResult = result;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#isFailed()
	 */
	@Override
	public boolean isFailed() {
		return failed;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setFailedState()
	 */
	@Override
	public void setFailedState() {
		failed = true;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#waitForCompletion()
	 */
	@Override
	public boolean waitForCompletion() throws InterruptedException {
		get();
		return true;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#fireCompleteEvent()
	 */
	@Override
	public void fireCompleteEvent() {
		
		// Is already be done
		if(done) {
			return;
		}
				
		// Run error handler
		if(errorCallback != null && failed) {
			final boolean couldBeHandled = errorCallback.handleError(this);
			if(couldBeHandled) {
				failed = false;
				return;
			}						
		}
				
		done = true;
		stopwatch.stop();
		latch.countDown();
		
		// Run success handler
		if(successCallback != null) {
			successCallback.accept(this);
		}		
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getMessage()
	 */
	@Override
	public String getMessage() {
		return message;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setMessage(java.lang.String)
	 */
	@Override
	public void setMessage(final String message) {
		this.message = message;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#isCompleteResult()
	 */
	@Override
	public boolean isCompleteResult() {
		return complete;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setCompleteResult(boolean)
	 */
	@Override
	public void setCompleteResult(final boolean complete) {
		this.complete = complete;
	}

	@Override
	public String toString() {
		return "NetworkOperationFutureImpl [requestId=" + requestId + ", operationResult=" + operationResult + ", latch="
				+ latch + ", failed=" + failed + ", done=" + done + ", complete=" + complete + ", message=" + message
				+ ", stopwatch=" + stopwatch + ", connection=" + connection + "]";
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getCompletionTime(java.util.concurrent.TimeUnit)
	 */
	@Override
	public long getCompletionTime(final TimeUnit timeUnit) {
		if (! isDone()) {
			throw new IllegalArgumentException("The future is not done. Unable to calculate completion time");
		}
		
		return stopwatch.elapsed(timeUnit);
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getConnection()
	 */
	@Override
	public BBoxDBConnection getConnection() {
		return connection;
	}
	
	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getTransmittedPackage()
	 */
	@Override
	public NetworkRequestPackage getTransmittedPackage() {
		return lastTransmittedPackage;
	}

	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getMessageWithConnectionName()
	 */
	@Override
	public String getMessageWithConnectionName() {
		final StringBuilder sb = new StringBuilder();
		sb.append("[message=");
		sb.append(getMessage());
		sb.append(", connection=");
		
		if(getConnection() == null) {
			sb.append("null");
		} else {
			sb.append(connection.getConnectionName());
		}
		
		sb.append("]");
		return sb.toString();
	}
	
	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setErrorCallback(org.bboxdb.network.client.future.FutureErrorCallback)
	 */
	@Override
	public void setErrorCallback(final FutureErrorCallback errorCallback) {
		this.errorCallback = errorCallback;
	}
	
	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#setSuccessCallback(java.util.function.Consumer)
	 */
	@Override
	public void setSuccessCallback(final Consumer<NetworkOperationFutureImpl> successCallback) {
		this.successCallback = successCallback;
	}
	
	/* (non-Javadoc)
	 * @see org.bboxdb.network.client.future.NetworkOperationFuture#getExecutions()
	 */
	@Override
	public int getExecutions() {
		return executions.get();
	}
	
}

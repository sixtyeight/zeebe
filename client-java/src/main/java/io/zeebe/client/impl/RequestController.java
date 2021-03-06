/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.zeebe.client.clustering.impl.ClientTopologyManager;
import io.zeebe.client.cmd.BrokerErrorException;
import io.zeebe.client.cmd.ClientCommandRejectedException;
import io.zeebe.client.cmd.ClientException;
import io.zeebe.client.impl.cmd.CommandImpl;
import io.zeebe.client.impl.cmd.ReceiverAwareResponseResult;
import io.zeebe.client.task.impl.ControlMessageRequest;
import io.zeebe.protocol.clientapi.ErrorCode;
import io.zeebe.protocol.clientapi.ErrorResponseDecoder;
import io.zeebe.protocol.clientapi.MessageHeaderDecoder;
import io.zeebe.transport.ClientRequest;
import io.zeebe.transport.ClientTransport;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.util.buffer.BufferReader;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.state.SimpleStateMachineContext;
import io.zeebe.util.state.State;
import io.zeebe.util.state.StateMachine;
import io.zeebe.util.state.WaitState;
import io.zeebe.util.time.ClockUtil;

@SuppressWarnings("rawtypes")
public class RequestController implements BufferReader
{
    private static final long CMD_TIMEOUT = TimeUnit.SECONDS.toMillis(5);

    protected static final int TRANSITION_DEFAULT = 0;
    protected static final int TRANSITION_FAILED = 1;
    protected static final int TRANSITION_REFRESH_TOPOLOGY = 2;

    protected final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    protected final ErrorResponseDecoder errorResponseDecoder = new ErrorResponseDecoder();

    protected final StateMachine<Context> stateMachine;
    protected final DetermineRemoteState determineRemoteState = new DetermineRemoteState();
    protected final RefreshTopologyState refreshTopologyState = new RefreshTopologyState();
    protected final AwaitTopologyRefreshState awaitTopologyRefreshState = new AwaitTopologyRefreshState();
    protected final ExecuteRequestState executeRequestState = new ExecuteRequestState();
    protected final HandleResponseState handleResponseState = new HandleResponseState();
    protected final FinishedState finishedState = new FinishedState();
    protected final FailedState failedState = new FailedState();
    protected final ClosedState closedState = new ClosedState();

    protected final ClientTopologyManager topologyManager;

    protected CompletableFuture future;

    protected final ClientTransport transport;

    protected boolean isConfigured = false;

    private Consumer<RequestController> closeConsumer;

    protected final CommandRequestHandler commandRequestHandler;
    protected RequestResponseHandler currentRequestHandler;
    protected ControlMessageRequestHandler controlMessageHandler;

    public RequestController(
            final ClientTransport transport,
            final ClientTopologyManager topologyManager,
            final ObjectMapper objectMapper,
            Consumer<RequestController> closeConsumer)
    {
        this.transport = transport;
        this.topologyManager = topologyManager;
        this.closeConsumer = closeConsumer;
        this.commandRequestHandler = new CommandRequestHandler(objectMapper);
        this.controlMessageHandler = new ControlMessageRequestHandler(objectMapper);

        stateMachine = StateMachine.<Context>builder(Context::new)
            .initialState(closedState)
            .from(closedState).take(TRANSITION_DEFAULT).to(determineRemoteState)
            .from(determineRemoteState).take(TRANSITION_DEFAULT).to(executeRequestState)
            .from(determineRemoteState).take(TRANSITION_REFRESH_TOPOLOGY).to(refreshTopologyState)
            .from(determineRemoteState).take(TRANSITION_FAILED).to(failedState)
            .from(refreshTopologyState).take(TRANSITION_DEFAULT).to(awaitTopologyRefreshState)
            .from(awaitTopologyRefreshState).take(TRANSITION_DEFAULT).to(determineRemoteState)
            .from(awaitTopologyRefreshState).take(TRANSITION_FAILED).to(determineRemoteState)
            .from(executeRequestState).take(TRANSITION_DEFAULT).to(handleResponseState)
            .from(executeRequestState).take(TRANSITION_REFRESH_TOPOLOGY).to(refreshTopologyState)
            .from(executeRequestState).take(TRANSITION_FAILED).to(failedState)
            .from(handleResponseState).take(TRANSITION_DEFAULT).to(finishedState)
            .from(handleResponseState).take(TRANSITION_FAILED).to(failedState)
            .from(handleResponseState).take(TRANSITION_REFRESH_TOPOLOGY).to(refreshTopologyState)
            .from(finishedState).take(TRANSITION_DEFAULT).to(closedState)
            .from(failedState).take(TRANSITION_DEFAULT).to(closedState)
            .build();
    }

    public void configureCommandRequest(final CommandImpl command, final CompletableFuture future)
    {
        this.future = future;
        commandRequestHandler.configure(command);

        currentRequestHandler = commandRequestHandler;
        isConfigured = true;

    }

    public void configureControlMessageRequest(ControlMessageRequest controlMessage, CompletableFuture future)
    {
        this.future = future;
        controlMessageHandler.configure(controlMessage);

        currentRequestHandler = controlMessageHandler;
        isConfigured = true;

    }


    public int doWork()
    {
        return stateMachine.doWork();
    }

    public boolean isClosed()
    {
        return stateMachine.getCurrentState() == closedState && !isConfigured;
    }

    protected boolean shouldRetryRequestOnError(ErrorCode errorCode)
    {
        return ErrorCode.TOPIC_NOT_FOUND == errorCode || ErrorCode.REQUEST_TIMEOUT == errorCode;
    }

    @Override
    public void wrap(final DirectBuffer buffer, final int offset, final int length)
    {
        messageHeaderDecoder.wrap(buffer, 0);

        final int blockLength = messageHeaderDecoder.blockLength();
        final int version = messageHeaderDecoder.version();

        final int responseMessageOffset = messageHeaderDecoder.encodedLength();

        final Context context = stateMachine.getContext();

        if (currentRequestHandler.handlesResponse(messageHeaderDecoder))
        {
            final Object responseObject = currentRequestHandler.getResult(buffer, responseMessageOffset, blockLength, messageHeaderDecoder.version());

            // expose request channel if need to keep a reference of it (e.g. subscriptions)
            if (responseObject instanceof ReceiverAwareResponseResult)
            {
                ((ReceiverAwareResponseResult) responseObject).setReceiver(context.receiver);
            }

            context.responseObject = responseObject;
        }
        else
        {
            errorResponseDecoder.wrap(buffer, responseMessageOffset, blockLength, version);

            final int errorDataLength = errorResponseDecoder.errorDataLength();
            context.errorBuffer = BufferUtil.wrapArray(new byte[errorDataLength]);
            context.errorCode = errorResponseDecoder.errorCode();

            errorResponseDecoder.getErrorData(context.errorBuffer, 0, errorDataLength);
        }

    }

    private class DetermineRemoteState implements State<Context>
    {
        @Override
        public int doWork(final Context context) throws Exception
        {
            ++context.attempts;

            final RemoteAddress target = currentRequestHandler.getTarget(topologyManager);
            final long now = ClockUtil.getCurrentTimeInMillis();

            if (now > context.timeout)
            {
                context.exception = new ClientException(
                        "Cannot execute request (timeout). " +
                            "Request was: " + currentRequestHandler.describeRequest() +
                            ". Contacted brokers: " + context.contactedBrokers.toString(), context.exception);
                context.take(TRANSITION_FAILED);
                return 0;
            }

            if (target != null)
            {
                final ClientRequest request = transport.getOutput().sendRequest(target, currentRequestHandler);

                if (request != null)
                {
                    context.receiver = target;
                    context.contactedBrokers.add(target);
                    context.request = request;
                    context.take(TRANSITION_DEFAULT);
                }
            }
            else
            {
                context.take(TRANSITION_REFRESH_TOPOLOGY);
            }

            return 1;
        }

    }
    private class RefreshTopologyState implements State<Context>
    {

        @Override
        public int doWork(final Context context) throws Exception
        {
            context.topologyRefreshFuture = topologyManager.refreshNow();
            context.take(TRANSITION_DEFAULT);
            return 1;
        }

    }
    private static class AwaitTopologyRefreshState implements State<Context>
    {
        @Override
        public int doWork(final Context context) throws Exception
        {
            int workCount = 0;

            final CompletableFuture<Void> topologyRefreshFuture = context.topologyRefreshFuture;

            if (topologyRefreshFuture.isDone())
            {
                try
                {
                    topologyRefreshFuture.get();
                    context.take(TRANSITION_DEFAULT);
                }
                catch (final Exception e)
                {
                    context.exception = e;
                    context.take(TRANSITION_FAILED);
                }


                workCount += 1;
            }

            return workCount;
        }

    }

    private class ExecuteRequestState implements State<Context>
    {
        @Override
        public int doWork(final Context context) throws Exception
        {
            final ClientRequest request = context.request;

            if (request.isDone())
            {
                try
                {
                    final DirectBuffer response = request.get();
                    wrap(response, 0, response.capacity());

                    context.take(TRANSITION_DEFAULT);
                }
                catch (ClientCommandRejectedException e)
                {
                    context.exception = e;
                    context.take(TRANSITION_FAILED);
                }
                catch (Exception e)
                {
                    context.exception = new ClientException("Unexpected exception during response handling", e);
                    context.take(TRANSITION_FAILED);
                }
                finally
                {
                    request.close();
                }

                return 1;
            }
            else
            {
                // wait
                return 0;
            }
        }
    }

    private static class HandleResponseState implements State<Context>
    {

        @Override
        public int doWork(final Context context) throws Exception
        {
            final ErrorCode errorCode = context.errorCode;

            if (errorCode == ErrorCode.NULL_VAL)
            {
                // request was successful
                context.take(TRANSITION_DEFAULT);
            }
            else if (errorCode == ErrorCode.TOPIC_NOT_FOUND)
            {
                // reset error context
                context.errorCode = ErrorCode.NULL_VAL;
                context.errorBuffer = null;

                // topic not found -> refresh topology -> retry request
                context.take(TRANSITION_REFRESH_TOPOLOGY);
            }
            else
            {
                context.take(TRANSITION_FAILED);
            }

            return 1;
        }

    }

    private class FinishedState implements State<Context>
    {

        @Override
        public int doWork(final Context context) throws Exception
        {
            future.complete(context.responseObject);
            context.take(TRANSITION_DEFAULT);
            return 1;
        }

        @Override
        public void onExit()
        {
            closeConsumer.accept(RequestController.this);
        }
    }

    private class FailedState implements State<Context>
    {

        @Override
        public int doWork(final Context context) throws Exception
        {
            final ErrorCode errorCode = context.errorCode;
            Throwable exception = context.exception;

            if (errorCode != ErrorCode.NULL_VAL)
            {
                try
                {
                    final String errorMessage = BufferUtil.bufferAsString(context.errorBuffer);
                    exception = new BrokerErrorException(errorCode, errorMessage);
                }
                catch (final Exception e)
                {
                    exception = new BrokerErrorException(errorCode, "Unable to parse error message from response: " + e.getMessage());
                }
            }
            else if (exception == null)
            {
                exception = new RuntimeException("Unknown error during request execution");
            }

            future.completeExceptionally(exception);

            context.take(TRANSITION_DEFAULT);

            return 1;
        }

        @Override
        public void onExit()
        {
            closeConsumer.accept(RequestController.this);
        }
    }

    private class ClosedState implements WaitState<Context>
    {

        @Override
        public void work(final Context context) throws Exception
        {
            if (isConfigured)
            {
                context.reset();
                context.timeout = ClockUtil.getCurrentTimeInMillis() + CMD_TIMEOUT;
                isConfigured = false;
                context.take(TRANSITION_DEFAULT);
            }
        }
    }

    static class Context extends SimpleStateMachineContext
    {
        public ClientRequest request;
        protected Set<RemoteAddress> contactedBrokers = new HashSet<>();

        CompletableFuture<Void> topologyRefreshFuture;

        int attempts;
        Object responseObject;
        ErrorCode errorCode = ErrorCode.NULL_VAL;
        MutableDirectBuffer errorBuffer;
        Exception exception;
        long timeout;
        RemoteAddress receiver;

        Context(final StateMachine<?> stateMachine)
        {
            super(stateMachine);
        }

        @Override
        public void reset()
        {
            topologyRefreshFuture = null;
            attempts = 0;
            responseObject = null;
            errorCode = ErrorCode.NULL_VAL;
            errorBuffer = null;
            exception = null;
            contactedBrokers.clear();
        }

    }


}

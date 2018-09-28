/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.policy.impl;

import com.google.common.base.Throwables;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.core.processor.ProcessorContext;
import io.gravitee.gateway.core.processor.ProcessorFailure;
import io.gravitee.gateway.core.processor.StreamableProcessor;
import io.gravitee.gateway.policy.Policy;
import io.gravitee.gateway.policy.PolicyChainException;
import io.gravitee.gateway.policy.impl.processor.PolicyChainProcessorFailure;
import io.gravitee.policy.api.PolicyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class PolicyChain extends BufferedReadWriteStream
        implements io.gravitee.policy.api.PolicyChain, StreamableProcessor<PolicyResult> {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected static final PolicyResult SUCCESS_POLICY_CHAIN = new SuccessPolicyResult();

    protected Handler<PolicyResult> resultHandler;
    protected Handler<ProcessorFailure> errorHandler;
    protected Handler<ProcessorFailure> streamErrorHandler;
    protected final List<Policy> policies;
    protected final Iterator<Policy> policyIterator;
    protected final ExecutionContext executionContext;

    protected PolicyChain(List<Policy> policies, final ExecutionContext executionContext) {
        Objects.requireNonNull(policies, "Policies must not be null");
        Objects.requireNonNull(executionContext, "ExecutionContext must not be null");

        this.policies = policies;
        this.executionContext = executionContext;

        policyIterator = iterator();
    }

    @Override
    public void doNext(final Request request, final Response response) {
        if (policyIterator.hasNext()) {
            Policy policy = policyIterator.next();
            try {
                if (policy.isRunnable()) {
                    execute(policy, request, response, this, executionContext);
                } else {
                    doNext(request, response);
                }
            } catch (Exception ex) {
                logger.error("Unexpected error while running policy {}", policy, ex);
                request.metrics().setMessage(Throwables.getStackTraceAsString(ex));
                if (errorHandler != null) {
                    errorHandler.handle(new PolicyChainProcessorFailure(PolicyResult.failure(null)));
                }
            }
        } else {
            resultHandler.handle(null);
        }
    }

    @Override
    public void failWith(PolicyResult policyResult) {
        errorHandler.handle(new PolicyChainProcessorFailure(policyResult));
    }

    @Override
    public void streamFailWith(PolicyResult policyResult) {
        streamErrorHandler.handle(new PolicyChainProcessorFailure(policyResult));
    }

    @Override
    public void process(ProcessorContext context) {
        doNext(context.getRequest(), context.getResponse());
    }

    @Override
    public StreamableProcessor<PolicyResult> handler(Handler<PolicyResult> handler) {
        this.resultHandler = handler;
        return this;
    }

    @Override
    public StreamableProcessor<PolicyResult> errorHandler(Handler<ProcessorFailure> handler) {
        this.errorHandler = handler;
        return this;
    }

    @Override
    public StreamableProcessor<PolicyResult> streamErrorHandler(Handler<ProcessorFailure> handler) {
        this.streamErrorHandler = handler;
        return this;
    }

    protected abstract void execute(Policy policy, Object ... args) throws PolicyChainException;
    protected abstract Iterator<Policy> iterator();
}

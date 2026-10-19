package com.enterprise.agentharness.model;

import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ModelGateway {
    private final ModelProvider provider;
    private final ExecutorService executor;
    public ModelGateway(ModelProvider provider,@Qualifier("modelExecutor") ExecutorService executor) {this.provider=provider;this.executor=executor;}
    public ModelProvider.Result generate(ModelProvider.Input input,int maxOutputTokens,int timeoutMillis) {
        Future<ModelProvider.Result> future;
        try {future=executor.submit(()->provider.generate(input,maxOutputTokens));}
        catch(RejectedExecutionException ex) {throw new ModelFailure("PROVIDER_BUSY");}
        try {
            var result=future.get(timeoutMillis,TimeUnit.MILLISECONDS);
            if(result==null) throw new ModelFailure("PROVIDER_ERROR");
            return result;
        } catch(TimeoutException ex) {future.cancel(true);throw new ModelFailure("PROVIDER_TIMEOUT");}
        catch(InterruptedException ex) {future.cancel(true);Thread.currentThread().interrupt();throw new ModelFailure("CALL_INTERRUPTED");}
        catch(ExecutionException ex) {throw new ModelFailure("PROVIDER_ERROR");}
    }
}

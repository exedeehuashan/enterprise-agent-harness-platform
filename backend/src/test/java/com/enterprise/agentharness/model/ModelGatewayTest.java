package com.enterprise.agentharness.model;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelGatewayTest {
    private ModelProvider fixture(Callable<ModelProvider.Result> behavior) {
        return new ModelProvider(){
            public String id(){return "mock";}public String model(){return "fixture";}
            public Result generate(Input input,int max) throws Exception{return behavior.call();}
        };
    }
    @Test void timeoutInterruptsProviderAndReturnsOnlySafeCode() throws Exception {
        var interrupted=new CountDownLatch(1);
        var executor=new ModelConfiguration().modelExecutor();
        var provider=fixture(()->{
            try {new CountDownLatch(1).await();return null;}
            catch(InterruptedException ex){interrupted.countDown();throw ex;}
        });
        try {
            assertThatThrownBy(()->new ModelGateway(provider,executor).generate(new ModelProvider.Input("x",null),512,100))
                    .isInstanceOf(ModelFailure.class).hasMessage("PROVIDER_TIMEOUT").hasNoCause();
            assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
        } finally {executor.shutdownNow();}
    }
    @Test void exceptionDetailsAreNotExposed() {
        var executor=new ModelConfiguration().modelExecutor();
        try {
            assertThatThrownBy(()->new ModelGateway(fixture(()->{throw new IllegalStateException("PRIVATE_PROVIDER_ERROR");}),executor)
                    .generate(new ModelProvider.Input("x",null),512,1000))
                    .isInstanceOf(ModelFailure.class).hasMessage("PROVIDER_ERROR").hasNoCause();
        } finally {executor.shutdownNow();}
    }
    @Test void saturatedExecutorFailsFastWithoutGrowingQueueOrThreads() throws Exception {
        var executor=new ModelConfiguration().modelExecutor();
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try {
            for(int i=0;i<2;i++) executor.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->new ModelGateway(new MockModelProvider(),executor).generate(new ModelProvider.Input("x",null),512,1000))
                    .isInstanceOf(ModelFailure.class).hasMessage("PROVIDER_BUSY");
            assertThat(((ThreadPoolExecutor)executor).getPoolSize()).isEqualTo(2);
            assertThat(((ThreadPoolExecutor)executor).getQueue()).isEmpty();
        } finally {release.countDown();executor.shutdownNow();}
    }
}

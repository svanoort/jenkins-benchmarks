package org.jenkinsci.harness;

import java.util.concurrent.Callable;

public interface SingletonBenchmarkInterface<T> extends Callable<T> {

    /** Concrete implementation used for the benchmark itself
     *  Required because of the custom class changes involved with JMH
     */
    Class<? extends SingletonBenchmarkInterface> getTestClass();

    /** Override me to provide some per-invocation behavior using internals of this benchmark */
    void setupInvocation() throws Exception;

    /** Override me to provide some per-invocation behavior using internals of this benchmark */
    void tearDownInvocation() throws Exception;
}

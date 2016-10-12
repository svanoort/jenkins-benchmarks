package org.jenkinsci.harness;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** Extend this and implement the call<Type> method to create a benchmark class that runs the call as a benchmark */
@State(Scope.Benchmark)
public abstract class SingletonBenchmark<T> implements SingletonBenchmarkInterface {
    public JenkinsMaskedClassesRunner maskedClassesRunner = new JenkinsMaskedClassesRunner();

    public SingletonBenchmarkInterface<T> actualRunnable;

    /** HACK: needs to return the base class for the test, because JMH generates its own custom class to benchmark
     *  Which means that this.getClass() returns the custom class, and it won't be SingleTonBenchmark
     */
    public abstract Class<? extends SingletonBenchmark> getTestClass();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        maskedClassesRunner.startup();
        Class c = maskedClassesRunner.testLoader.loadClass(getTestClass().getName());
        Object o = c.newInstance();
        actualRunnable = (SingletonBenchmarkInterface)(o);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        maskedClassesRunner.shutdown();
    }

    @Benchmark
    public T benchmark() throws Exception {
        try {
            return actualRunnable.call();
        } catch (Exception ex) {
            throw ex;
        }
    }

    @Setup(Level.Invocation)  // Invocation-level for cases where you're testing for a test interval
    public void setupInvocationInvoker() throws Exception {
        actualRunnable.setupInvocation();  // Indirection due to classloading fun
    }

    /** Override me to provide some per-iteration behavior using internals of this benchmark */
    public void setupInvocation(){
        // NO-OP
    }

    @TearDown(Level.Invocation)  // Invocation-level for cases where you're testing for a test interval
    public void tearDownInvocationInvoker() throws Exception {
        actualRunnable.tearDownInvocation();  // Indirection due to classloading fun
    }

    /** Override me to provide some per-iteration behavior using internals of this benchmark */
    public void tearDownInvocation(){
        // NO-OP, override me to provide some per-iteration behavior
    }

    @Override public abstract T call() throws Exception;

}

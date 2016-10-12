package org.jenkinsci.harness;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.Callable;

/** Extend this and implement the call<Type> method to create a benchmark class that runs the call as a benchmark */
@State(Scope.Benchmark)
public abstract class SingletonBenchmark<T> extends BaseBenchmark implements Callable<T> {

    Callable<T> superRunnable;

    public void setup() throws Exception {
        super.setup();
        this.superRunnable = (SingletonBenchmark)this.actualRunnable;
    }

    @Benchmark
    public T benchmark() throws Exception {
        try {
            return (T)(superRunnable.call());
        } catch (Exception ex) {
            throw ex;
        }
    }

    @Override public abstract T call() throws Exception;

}

package org.jenkinsci.harness;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.Callable;

/** Extend this and implement the call<Type> method to create a benchmark class that runs the call as a benchmark */
@State(Scope.Benchmark)
public abstract class SingletonBenchmark<T> extends BaseBenchmark<SingletonBenchmark> implements Callable<T> {

    @Benchmark
    public T benchmark() throws Exception {
        try {
            return (T)(actualRunnable.call());
        } catch (Exception ex) {
            throw ex;
        }
    }

    @Override public abstract T call() throws Exception;

}

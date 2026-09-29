package com.selfdriving.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.selfdriving.simulation.Simulation;

/** Runs a question on the simulation thread and waits for the answer (the simulation loop must be running). */
final class OnSimulation {

    private static final long TIMEOUT_MS = 3000;

    private OnSimulation() {
    }

    static <T> T call(Simulation simulation, Function<Simulation, T> question) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        simulation.submit(s -> {
            try {
                answer.complete(question.apply(s));
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            return answer.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the car", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The car did not answer in time", e);
        }
    }

    static void run(Simulation simulation, java.util.function.Consumer<Simulation> command) {
        call(simulation, s -> {
            command.accept(s);
            return null;
        });
    }
}

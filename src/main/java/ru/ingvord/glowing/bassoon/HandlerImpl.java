package ru.ingvord.glowing.bassoon;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

public class HandlerImpl implements Handler {
    private final Client client;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);//TODO tune


    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        var service1 = new Callable<Response>(){
            @Override
            public Response call() {
                return client.getApplicationStatus1(id);
            }
        };

        var service2 = new Callable<Response>(){
            @Override
            public Response call() {
                return client.getApplicationStatus2(id);
            }
        };


        var future1 = CompletableFuture.supplyAsync(service1::call);
        var future2 = CompletableFuture.supplyAsync(service2::call);

        var status1 = future1.thenCompose(handleRetryResponse(service1));
        var status2 = future2.thenCompose(handleRetryResponse(service1));

        CompletableFuture<Response> anyOf = CompletableFuture.anyOf(status1, status2)
                .completeOnTimeout(new ApplicationStatusResponse.Failure(null, 1),15, TimeUnit.SECONDS)
                .thenApply(response -> (Response) response);

        try {
            Response response = anyOf.get();
            if (response instanceof Response.Success) {
                Response.Success success = (Response.Success) response;
                return new ApplicationStatusResponse.Success(success.applicationId(), success.applicationStatus());
            } else if (response instanceof Response.Failure) {
                Response.Failure failure = (Response.Failure) response;
                return new ApplicationStatusResponse.Failure(null, 1); // Simplified for example purposes
            }
        } catch (InterruptedException | ExecutionException e) {
            // Handle execution exceptions here
            return new ApplicationStatusResponse.Failure(null, 1);
        }

        // Fallback, in case no conditions are met
        return new ApplicationStatusResponse.Failure(null, 0);
    }

    @NotNull
    private Function<Response, CompletionStage<Response>> handleRetryResponse(Callable<Response> service) {
        return response -> {
            if (response instanceof Response.RetryAfter) {
                Response.RetryAfter retryAfter = (Response.RetryAfter) response;
                return delay(retryAfter.delay().toMillis()).thenCompose(v -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return service.call();
                    } catch (Exception e) {
                        return new Response.Failure(e);
                    }
                }));
            } else {
                return CompletableFuture.completedFuture(response);
            }
        };
    }

    private CompletableFuture<Void> delay(long delay) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(null), delay, TimeUnit.MILLISECONDS);
        return future;
    }
}

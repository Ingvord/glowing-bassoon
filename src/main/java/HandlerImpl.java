import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class HandlerImpl implements Handler {
    private final Client client;

    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        var service1 = new UpstreamService(id, client::getApplicationStatus1, Executors.newScheduledThreadPool(1));

        var service2 = new UpstreamService(id, client::getApplicationStatus2, Executors.newScheduledThreadPool(1));


        var future1 = CompletableFuture.supplyAsync(service1::callServiceWithRetry);
        var future2 = CompletableFuture.supplyAsync(service2::callServiceWithRetry);


        CompletableFuture<Response> anyOf = CompletableFuture.anyOf(future1, future2)
                .completeOnTimeout(new ApplicationStatusResponse.Failure(null, 1),15, TimeUnit.SECONDS)
                .thenApply(response -> (Response) response);

        try {
            Response response = anyOf.get();
            if (response instanceof Response.Success) {
                Response.Success success = (Response.Success) response;
                return new ApplicationStatusResponse.Success(success.applicationId(), success.applicationStatus());
            } else if (response instanceof Response.Failure) {
                Response.Failure failure = (Response.Failure) response;
                return getFailure(service1, service2);
            }
        } catch (InterruptedException | ExecutionException e) {
            // Handle execution exceptions here
            return getFailure(service1, service2);
        }

        // Fallback, in case no conditions are met
        return getFailure(service1, service2);
    }

    private ApplicationStatusResponse getFailure(UpstreamService service1, UpstreamService service2){
        return new ApplicationStatusResponse.Failure(
                Duration.between(
                        Instant.ofEpochMilli(Math.max(service1.lastRequestTime.toEpochMilli(), service2.lastRequestTime.toEpochMilli())),
                        Instant.now()), Math.max(service1.getRetriesCount(), service2.getRetriesCount())); // Simplified for example purposes
    }

    private class UpstreamService {
        private final String id;
        private final Function<String, Response> serviceFunction;
        private final ScheduledExecutorService scheduler;
        private final AtomicInteger retriesCount = new AtomicInteger(0);
        private Instant lastRequestTime;

        public UpstreamService(String id, Function<String, Response> serviceFunction, ScheduledExecutorService scheduler) {
            this.id = id;
            this.serviceFunction = serviceFunction;
            this.scheduler = scheduler;
        }

        public CompletableFuture<Response> callServiceWithRetry() {
            return CompletableFuture.supplyAsync(this::callService)
                    .thenCompose(this::handleResponse);
        }

        private Response callService() {
            lastRequestTime = Instant.now();
            retriesCount.incrementAndGet();
            return serviceFunction.apply(id);
        }

        private CompletableFuture<Response> handleResponse(Response response) {
            if (response instanceof Response.RetryAfter retryAfter) {
                return delay(retryAfter.delay().toMillis())
                        .thenCompose(v -> callServiceWithRetry());
            } else {
                return CompletableFuture.completedFuture(response);
            }
        }

        private CompletableFuture<Void> delay(long delay) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.schedule(() -> future.complete(null), delay, TimeUnit.MILLISECONDS);
            return future;
        }

        public int getRetriesCount() {
            return retriesCount.get();
        }

        public Instant getLastRequestTime() {
            return lastRequestTime;
        }
    }

}

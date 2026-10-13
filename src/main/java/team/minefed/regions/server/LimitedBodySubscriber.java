package team.minefed.regions.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds memory while the response arrives; RegionApi also bounds the whole body read time. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit) { this.limit=limit; }
    @Override public CompletionStage<byte[]> getBody() { return completed; }
    @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
    @Override public void onNext(List<ByteBuffer> chunks) {
        if(completed.isDone()) return;
        for(ByteBuffer chunk:chunks) {
            if(chunk.remaining()>limit-bytes.size()) {
                subscription.cancel(); completed.completeExceptionally(new IOException("API response exceeds size limit"));return;
            }
            byte[] segment=new byte[chunk.remaining()];chunk.get(segment);bytes.writeBytes(segment);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
    @Override public void onComplete() { completed.complete(bytes.toByteArray()); }
}

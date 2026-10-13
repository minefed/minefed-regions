package team.minefed.regions.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class RegionApiTest {
    @Test void streamedBodyCannotExceedLimit() {
        LimitedBodySubscriber subscriber=new LimitedBodySubscriber(3);
        boolean[] cancelled={false};
        subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
        assertTrue(cancelled[0]);assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void authAndMalformedResponseAreRejectedWithoutReplacingState() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/regions/minefed-overworld/snapshot",exchange->{
            assertEquals("Bearer local-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="{\"revision\":0,\"regions\":[null],\"globalAdmins\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            ServerConfig config=new ServerConfig();config.apiBaseUrl="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,()->new RegionApi(config,"local-test-token").snapshot());
        } finally {server.stop(0);}
    }
    @Test void stalledResponseBodyHasWholeRequestDeadline() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            try {Thread.sleep(600);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();
        try {
            ServerConfig config=new ServerConfig();config.apiBaseUrl="http://127.0.0.1:"+server.getAddress().getPort();
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(java.io.IOException.class,
                    ()->new RegionApi(config,"local-test-token",Duration.ofMillis(150)).snapshot()));
        } finally {server.stop(0);}
    }
}

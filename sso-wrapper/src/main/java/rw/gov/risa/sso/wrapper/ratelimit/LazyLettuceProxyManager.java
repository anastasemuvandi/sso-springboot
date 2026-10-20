package rw.gov.risa.sso.wrapper.ratelimit;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.github.bucket4j.distributed.proxy.RemoteAsyncBucketBuilder;

/** Defers the Redis connection to first use and retries it after a failure. */
final class LazyLettuceProxyManager implements ProxyManager<byte[]> {

    private final Supplier<ProxyManager<byte[]>> factory;
    private volatile ProxyManager<byte[]> delegate;

    LazyLettuceProxyManager(Supplier<ProxyManager<byte[]>> factory) {
        this.factory = factory;
    }

    private ProxyManager<byte[]> delegate() {
        ProxyManager<byte[]> d = delegate;
        if (d == null) {
            synchronized (this) {
                if (delegate == null) {
                    delegate = factory.get();
                }
                d = delegate;
            }
        }
        return d;
    }

    @Override
    public RemoteBucketBuilder<byte[]> builder() {
        try {
            return delegate().builder();
        } catch (RuntimeException e) {
            delegate = null;
            throw e;
        }
    }

    @Override
    public Optional<BucketConfiguration> getProxyConfiguration(byte[] key) {
        return delegate().getProxyConfiguration(key);
    }

    @Override
    public void removeProxy(byte[] key) {
        delegate().removeProxy(key);
    }

    @Override
    public boolean isAsyncModeSupported() {
        return delegate().isAsyncModeSupported();
    }

    @Override
    public AsyncProxyManager<byte[]> asAsync() {
        return delegate().asAsync();
    }

    @Override
    public boolean isExpireAfterWriteSupported() {
        return true;
    }
}

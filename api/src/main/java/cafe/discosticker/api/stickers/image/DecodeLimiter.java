package cafe.discosticker.api.stickers.image;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/**
 * Caps how many images are decoded at once, which bounds decode memory (design D6). Waiters are
 * served in arrival order.
 */
public final class DecodeLimiter {

	/** A held decode permit; closing it (once) lets the next upload decode. */
	public interface Permit extends AutoCloseable {

		@Override
		void close();

	}

	private final Semaphore permits;

	private final long waitNanos;

	public DecodeLimiter(int permits, Duration wait) {
		this.permits = new Semaphore(permits, true);
		this.waitNanos = wait.toNanos();
	}

	/**
	 * Waits up to the configured time for a permit.
	 * @throws ImageRejectedException {@code BUSY} when none frees up in time
	 */
	public Permit acquire() {
		try {
			if (!this.permits.tryAcquire(this.waitNanos, TimeUnit.NANOSECONDS)) {
				throw new ImageRejectedException(Reason.BUSY);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ImageRejectedException(Reason.BUSY, ex);
		}
		AtomicBoolean released = new AtomicBoolean();
		return () -> {
			if (released.compareAndSet(false, true)) {
				this.permits.release();
			}
		};
	}

}

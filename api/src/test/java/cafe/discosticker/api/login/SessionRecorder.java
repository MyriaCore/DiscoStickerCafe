package cafe.discosticker.api.login;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

/**
 * Records every HTTP session Tomcat creates, the IDs it has had, every attribute ever stored in
 * it, and whether it was destroyed. Lets tests prove that a rejected login never stored a
 * signed-in user and that its pre-login session was invalidated.
 */
public class SessionRecorder implements HttpSessionListener, HttpSessionIdListener, HttpSessionAttributeListener {

	/** What happened to one session. {@link #session()} stays readable while it is live. */
	public static final class Recorded {

		private final HttpSession session;

		private final List<String> ids = new CopyOnWriteArrayList<>();

		private final Set<String> attributeNames = ConcurrentHashMap.newKeySet();

		private final List<String> attributeValues = new CopyOnWriteArrayList<>();

		private final int maxInactiveAtCreation;

		private volatile boolean destroyed;

		Recorded(HttpSession session) {
			this.session = session;
			this.ids.add(session.getId());
			this.maxInactiveAtCreation = session.getMaxInactiveInterval();
		}

		public HttpSession session() {
			return this.session;
		}

		public Set<String> attributeNames() {
			return Set.copyOf(this.attributeNames);
		}

		/** {@code String.valueOf} of every value ever stored, at the time it was stored. */
		public List<String> attributeValues() {
			return List.copyOf(this.attributeValues);
		}

		public int maxInactiveAtCreation() {
			return this.maxInactiveAtCreation;
		}

		public boolean destroyed() {
			return this.destroyed;
		}

	}

	private final Map<HttpSession, Recorded> sessions = Collections.synchronizedMap(new IdentityHashMap<>());

	/** The session that has, or once had, this ID. */
	public Optional<Recorded> byId(String id) {
		synchronized (this.sessions) {
			return this.sessions.values().stream().filter((recorded) -> recorded.ids.contains(id)).findFirst();
		}
	}

	public List<Recorded> all() {
		synchronized (this.sessions) {
			return new ArrayList<>(this.sessions.values());
		}
	}

	@Override
	public void sessionCreated(HttpSessionEvent event) {
		this.sessions.put(event.getSession(), new Recorded(event.getSession()));
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent event) {
		Recorded recorded = this.sessions.get(event.getSession());
		if (recorded != null) {
			recorded.destroyed = true;
		}
	}

	@Override
	public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
		Recorded recorded = this.sessions.get(event.getSession());
		if (recorded != null) {
			recorded.ids.add(event.getSession().getId());
		}
	}

	@Override
	public void attributeAdded(HttpSessionBindingEvent event) {
		record(event, event.getValue());
	}

	@Override
	public void attributeReplaced(HttpSessionBindingEvent event) {
		// For a replacement, the event carries the old value; the session holds the new one.
		record(event, event.getSession().getAttribute(event.getName()));
	}

	private void record(HttpSessionBindingEvent event, Object value) {
		Recorded recorded = this.sessions.get(event.getSession());
		if (recorded != null) {
			recorded.attributeNames.add(event.getName());
			recorded.attributeValues.add(String.valueOf(value));
		}
	}

}

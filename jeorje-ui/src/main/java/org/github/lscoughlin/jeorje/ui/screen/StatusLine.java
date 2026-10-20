package org.github.lscoughlin.jeorje.ui.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/** The one-line status bar. All methods are thread safe. */
public final class StatusLine {
    private static final int MAX_NOTICES = 3;

    private String agent = "";
    private String state = "idle";
    private String model = "";
    private int contextPercent = -1;
    private int peers;
    private String branch = "";
    private boolean yolo;
    private boolean plan;
    private int implementDone = -1;
    private int implementTotal;
    private final Deque<String> notices = new ArrayDeque<>();
    private String follow;

    public synchronized StatusLine agent(String name) { this.agent = name; return this; }

    public synchronized StatusLine state(String state) { this.state = state; return this; }

    public synchronized StatusLine model(String model) { this.model = model; return this; }

    /** The context use as a percent of the budget. A negative value hides the field. */
    public synchronized StatusLine contextPercent(int percent) { this.contextPercent = percent; return this; }

    public synchronized StatusLine peers(int peers) { this.peers = peers; return this; }

    public synchronized StatusLine branch(String branch) { this.branch = branch == null ? "" : branch; return this; }

    public synchronized StatusLine yolo(boolean on) { this.yolo = on; return this; }

    public synchronized StatusLine plan(boolean on) { this.plan = on; return this; }

    /** Shows {@code IMPLEMENT n/m}. */
    public synchronized StatusLine implement(int done, int total) {
        this.implementDone = done;
        this.implementTotal = total;
        return this;
    }

    public synchronized StatusLine clearImplement() { this.implementDone = -1; return this; }

    /** Adds a notice from a background agent. Only the last three notices stay. */
    public synchronized StatusLine notice(String text) {
        notices.addLast(text);
        while (notices.size() > MAX_NOTICES) notices.removeFirst();
        return this;
    }

    /** Sets the follow notice for new chat lines, or null to remove it. It shows at the start of the line. */
    public synchronized StatusLine follow(String text) { this.follow = text; return this; }

    /** The follow notice, or null. */
    public synchronized String follow() { return follow; }

    public synchronized StatusLine clearNotices() { notices.clear(); return this; }

    /** The plain text of the status line, without padding. */
    public synchronized String text() {
        var parts = new ArrayList<String>();
        if (follow != null) parts.add(follow);
        parts.add(agent.isEmpty() ? state : agent + " [" + state + "]");
        if (!branch.isEmpty()) parts.add(branch);
        if (yolo) parts.add("YOLO");
        if (plan) parts.add("PLAN");
        if (implementDone >= 0) parts.add("IMPLEMENT " + implementDone + "/" + implementTotal);
        if (!model.isEmpty()) parts.add(model);
        if (contextPercent >= 0) parts.add("ctx " + contextPercent + "%");
        parts.add("peers " + peers);
        if (!notices.isEmpty()) parts.add(String.join(" ; ", notices));
        return String.join(" | ", parts);
    }

    /** The styled line, cut or padded to the width. */
    public AttributedString render(int width) {
        var w = Math.max(1, width);
        var s = text();
        if (s.length() > w) s = s.substring(0, Math.max(0, w - 1)) + "…";
        var padded = s + " ".repeat(Math.max(0, w - s.length()));
        var style = contextPercent() >= WARN_PERCENT
                ? AttributedStyle.DEFAULT.inverse().foreground(AttributedStyle.YELLOW) : AttributedStyle.DEFAULT.inverse();
        return new AttributedString(padded, style);
    }

    /** The context use at which the status line changes its color. */
    public static final int WARN_PERCENT = 85;

    /** The context use in percent, or -1 when it is unknown. */
    public synchronized int contextPercent() { return contextPercent; }
}

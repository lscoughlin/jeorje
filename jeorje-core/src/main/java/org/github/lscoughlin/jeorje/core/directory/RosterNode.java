package org.github.lscoughlin.jeorje.core.directory;

import java.util.List;

/**
 * One node of the roster tree. The tree has three levels: a harness (one instance of Jeorje), the agents of
 * that harness, and the subagents of each agent. A subagent can have subagents of its own.
 *
 * @param kind the level of the node
 * @param id the id of the node; for an agent or a subagent it is the id that the switcher uses
 * @param label the display name
 * @param detail extra text such as the state or the address, may be empty
 * @param children the child nodes in display order
 */
public record RosterNode(RosterKind kind, String id, String label, String detail, List<RosterNode> children) {

    public RosterNode {
        detail = detail == null ? "" : detail;
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** True when the node has children. */
    public boolean hasChildren() {
        return !children.isEmpty();
    }
}

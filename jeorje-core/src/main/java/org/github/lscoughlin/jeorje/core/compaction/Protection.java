package org.github.lscoughlin.jeorje.core.compaction;

/** The protection class of a chunk. It decides how far compaction can change the chunk. */
public enum Protection {
    /** Never changed. The level stays 0. */
    PROTECTED(0),
    /** Pruned with care. The level stops at 2. */
    CAREFUL(2),
    /** Pruned or dropped. The level can reach 3. */
    AGGRESSIVE(3);

    private final int maxLevel;

    Protection(int maxLevel) { this.maxLevel = maxLevel; }

    /** The highest pruning level that this class allows. */
    public int maxLevel() { return maxLevel; }
}

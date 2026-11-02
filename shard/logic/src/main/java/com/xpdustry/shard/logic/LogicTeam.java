package com.xpdustry.shard.logic;

/**
 * A team as mlog holds it, vanilla's {@code Team}: what {@code @sharded}, {@code lookup team} and a building's
 * {@code @team} give, and what {@code print} writes by name. A {@link Globals} table holds one per id, so variables
 * compare them by identity; a host turns its team ids into teams with {@link Globals#team(int)}.
 */
public record LogicTeam(int id, String name){
}

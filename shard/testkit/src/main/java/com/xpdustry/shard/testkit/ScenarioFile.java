package com.xpdustry.shard.testkit;

import java.util.*;

/** A scenario fixture as committed in YAML: the scenario and, optionally, the tolerances its diff runs under. */
public record ScenarioFile(Scenario scenario, Tolerances tolerances){
    public ScenarioFile{
        Objects.requireNonNull(scenario, "scenario");
    }

    public static ScenarioFile of(Scenario scenario){
        return new ScenarioFile(scenario, null);
    }
}

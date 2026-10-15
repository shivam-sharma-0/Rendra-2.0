package com.code.pal.agent.route;

/**
 * How big a run's job is, decided by rules before it starts. {@code S}: a small edit or a tiny site;
 * {@code M}: a new feature or an app with a few pages; {@code L}: a multi-page app with data or a server.
 * M and L runs get the longer time limit and the larger call budgets.
 */
public enum SizeTier { S, M, L }

/**
 * The admin model: bans, admins, the whitelist, kicks, name bans, player lookups and trace data, all in the store and
 * shared by every world. No packets: the connect flow calls {@link com.xpdustry.shard.server.admin.AdminService#admit}
 * and sends the {@link com.xpdustry.shard.server.admin.KickReason} it returns; {@code server.intent} carries out an
 * admin's {@code adminRequest}; the console's commands are {@code server.console.AdminCommands}.
 *
 * <p>Vanilla's {@code Administration} ({@code Administration.java}) and what serves it here:
 * <ul>
 *   <li>{@code banPlayerID}, {@code banPlayerIP}, {@code unbanPlayerID}, {@code unbanPlayerIP}: {@code ban},
 *       {@code banPlayer}, {@code banPlayerAt}, {@code unban}, {@code unbanPlayer}, {@code unbanAddress},
 *       {@code revoke}; bans can expire and carry a reason, an author and a revoker.</li>
 *   <li>{@code subnetBans}: {@code ban(SUBNET, ...)}, with CIDR blocks instead of string prefixes.</li>
 *   <li>{@code bannedNames}: {@code banName}, {@code unbanName}, {@code clearNameBans}, with a bounded match.</li>
 *   <li>{@code handleKicked}, {@code getKickTime}, {@code pardon}: {@code kicked}, {@code kickedUntil},
 *       {@code pardon}, {@code pardonPlayer}; every kick with a cooldown is stored with its reason.</li>
 *   <li>{@code adminPlayer}, {@code unAdminPlayer}, {@code getAdmins}: {@code grantAdmin}, {@code revokeAdmin},
 *       {@code admins}; {@link com.xpdustry.shard.server.admin.SetAdmin} changes an online player's flag.</li>
 *   <li>{@code whitelist}, {@code unwhitelist}, {@code getWhitelisted}: {@code whitelist}, {@code unwhitelist},
 *       {@code whitelisted}.</li>
 *   <li>{@code PlayerInfo}, {@code findByName}, {@code searchNames}: {@code player}, {@code info}, {@code search};
 *       names, addresses, joins, kicks and when the player was last seen. The in-game trace: {@code trace}.</li>
 *   <li>{@code dosBlacklist}: {@code dosBan}, {@code dosUnban}, in memory as in vanilla.</li>
 *   <li>Not here: the player limit (the {@code net.player-limit} key) and the chat and action filters
 *       ({@code server.chat}, {@code server.intent.action}).</li>
 * </ul>
 *
 * <p>Owner: WP50.
 */
package com.xpdustry.shard.server.admin;

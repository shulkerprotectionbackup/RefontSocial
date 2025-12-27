package ru.rizonchik.refontsocial.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

public final class MysqlStorage extends SqlStorage {

    public MysqlStorage(JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    protected boolean isMysql() {
        return true;
    }

    @Override
    protected HikariConfig buildConfig() {
        String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
        String database = plugin.getConfig().getString("storage.mysql.database", "refontsocial");
        String user = plugin.getConfig().getString("storage.mysql.username", "root");
        String pass = plugin.getConfig().getString("storage.mysql.password", "password");
        boolean useSSL = plugin.getConfig().getBoolean("storage.mysql.useSSL", false);
        String tz = plugin.getConfig().getString("storage.mysql.serverTimezone", "UTC");
        String params = plugin.getConfig().getString("storage.mysql.params", "");

        String jdbc = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=" + useSSL +
                "&serverTimezone=" + tz +
                (params.isEmpty() ? "" : "&" + params);

        int maxPool = plugin.getConfig().getInt("storage.mysql.pool.maximumPoolSize", 10);
        int minIdle = plugin.getConfig().getInt("storage.mysql.pool.minimumIdle", 2);
        long connTimeout = plugin.getConfig().getLong("storage.mysql.pool.connectionTimeoutMs", 10000L);
        long idleTimeout = plugin.getConfig().getLong("storage.mysql.pool.idleTimeoutMs", 600000L);
        long maxLifetime = plugin.getConfig().getLong("storage.mysql.pool.maxLifetimeMs", 1800000L);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbc);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(Math.max(2, maxPool));
        cfg.setMinimumIdle(Math.max(0, minIdle));
        cfg.setConnectionTimeout(Math.max(1000L, connTimeout));
        cfg.setIdleTimeout(Math.max(10000L, idleTimeout));
        cfg.setMaxLifetime(Math.max(30000L, maxLifetime));

        cfg.setPoolName("RefontSocial-MySQL");

        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return cfg;
    }
}
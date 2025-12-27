package ru.rizonchik.refontsocial.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SqliteStorage extends SqlStorage {

    public SqliteStorage(JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    protected boolean isMysql() {
        return false;
    }

    @Override
    protected HikariConfig buildConfig() {
        String fileName = plugin.getConfig().getString("storage.sqlite.file", "data.db");
        File file = new File(plugin.getDataFolder(), fileName);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        cfg.setMaximumPoolSize(1);
        cfg.setPoolName("RefontSocial-SQLite");
        cfg.setConnectionTestQuery("SELECT 1");
        return cfg;
    }
}
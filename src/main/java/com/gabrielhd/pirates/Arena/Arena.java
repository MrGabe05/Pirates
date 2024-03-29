package com.gabrielhd.pirates.Arena;

import com.gabrielhd.pirates.Events.FinishGameEvent;
import com.gabrielhd.pirates.Events.StartGameEvent;
import com.google.common.collect.*;
import com.gabrielhd.pirates.Teams.*;
import com.gabrielhd.pirates.Config.*;
import com.gabrielhd.pirates.*;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.inventory.*;
import com.gabrielhd.pirates.Utils.*;
import com.gabrielhd.pirates.Player.*;
import org.bukkit.entity.*;
import org.bukkit.potion.*;
import com.gabrielhd.pirates.Tasks.*;
import java.util.*;
import java.util.logging.Level;

import org.bukkit.*;
import org.bukkit.enchantments.*;

public class Arena {
    
    @Getter private final String name;

    @Getter @Setter private boolean enable;
    @Getter @Setter private boolean starting;
    @Getter @Setter private boolean started;
    @Getter @Setter private boolean force;

    @Getter @Setter private ArenaState state;

    @Getter @Setter private int minPlayers;
    @Getter @Setter private int maxPlayers;
    @Getter @Setter private int startTime;
    @Getter @Setter private int endTime;
    @Getter @Setter private Location spawn;
    @Getter @Setter private Location specs;
    @Getter @Setter private Cuboid cuboid;
    @Getter @Setter private StartTask startTask;
    
    @Getter private final BlockTracker blockTracker;
    @Getter private final List<Player> alivePlayers;
    @Getter private final List<Player> players;
    @Getter @Setter private List<Team> teams;
    @Getter @Setter private List<Integer> broadcast;

    private final Pirates plugin;
    
    public Arena(Pirates plugin, String name) {
        this.name = name;
        this.enable = false;
        this.endTime = 15;
        this.startTime = 10;
        this.minPlayers = 6;
        this.maxPlayers = 10;
        this.starting = false;
        this.started = false;
        this.players = Lists.newArrayList();
        this.alivePlayers = Lists.newArrayList();
        this.broadcast = Lists.newArrayList(60, 30, 20, 15, 10, 5, 4, 3, 2, 1 );
        this.blockTracker = new BlockTracker();

        this.state = ArenaState.WAITING;
        this.teams = Lists.newArrayList(new Team(TeamColor.RED), new Team(TeamColor.BLUE));

        this.plugin = plugin;
    }

    public boolean isFull() {
        return this.players.size() >= this.maxPlayers;
    }

    public boolean canStart() {
        return this.players.size() >= this.minPlayers;
    }

    public void addPlayer(Player player) {
        YamlConfig settings = this.plugin.getConfigManager().getSettings();
        YamlConfig messages = this.plugin.getConfigManager().getMessages();
        YamlConfig items = new YamlConfig(this.plugin, "Items");

        if (!this.enable) {
            player.sendMessage(Utils.Color(messages.getString("ArenaIsDisable").replace("%arena%", this.name)));
            return;
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        PlayerData playerData = this.plugin.getPlayerManager().getPlayerData(player);
        if (playerData != null) {
            playerData.setLives(settings.getInt("Settings.Lives", 1));
            playerData.setState(PlayerState.PLAYING);
            playerData.setArena(this);
        }

        if (this.spawn != null) {
            player.teleport(this.spawn);
        }

        player.setFoodLevel(20);
        player.setHealth(player.getMaxHealth());
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGameMode(GameMode.SURVIVAL);

        for(Player ingame : this.players) {
            ingame.showPlayer(player);
            player.showPlayer(ingame);
        }

        for (PotionEffect potionEffect : player.getActivePotionEffects()) {
            player.removePotionEffect(potionEffect.getType());
        }

        try {
            if(items.isSet("Items.PreGame.Leave") && items.getBoolean("Items.PreGame.Leave.Enable", true)) {
                player.getInventory().setItem(items.getInt("Items.PreGame.Leave.Slot", 8), ItemUtils.createItem(items.getString("Items.PreGame.Leave.ID", "RED_BED"), items.getString("Items.PreGame.Leave.Name", "&c&lLeave"), 1, items.getStringList("Items.PreGame.Leave.Lore"), items.getInt("Items.Pregame.Leave.Data")));
            }
        } catch (NullPointerException ex) {
            ex.printStackTrace();

            this.plugin.getLogger().log(Level.SEVERE, "The configuration for the Leave item is wrong, please check your configuration or contact the developer!");
        }

        this.players.add(player);
        this.alivePlayers.add(player);
        this.sendMessage(messages.getString("JoinArena").replace("%player%", player.getName()).replace("%players%", String.valueOf(this.players.size())).replace("%max-players%", String.valueOf(this.maxPlayers)));

        if (canStart() && this.state == ArenaState.WAITING && !this.starting && !this.started) {
            this.starting = true;

            this.state = ArenaState.STARTING;
            this.startTask = new StartTask(this.plugin, this);
        }
    }
    
    public void removePlayer(Player player, boolean disconnect) {
        YamlConfig settings = this.plugin.getConfigManager().getSettings();
        YamlConfig messages = this.plugin.getConfigManager().getMessages();
        YamlConfig items = new YamlConfig(this.plugin, "Items");

        if (this.players.contains(player)) {
            if (!disconnect && player.isOnline()) {
                PlayerData playerData = this.plugin.getPlayerManager().getPlayerData(player);
                if (playerData != null) {
                    playerData.setState(PlayerState.LOBBY);
                    playerData.setArena(null);
                }

                player.getInventory().clear();
                player.getInventory().setArmorContents(null);
                player.setAllowFlight(false);
                player.setFlying(false);
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setGameMode(GameMode.ADVENTURE);

                for(Player online : Bukkit.getOnlinePlayers()) {
                    online.showPlayer(player);
                    player.showPlayer(player);
                }
            }

            if (this.state != ArenaState.ENDING && this.state != ArenaState.PLAYING) {
                this.sendMessage(messages.getString("QuitArena").replace("%player%", player.getName()).replace("%players%", String.valueOf(this.players.size() - 1)).replace("%max-players%", String.valueOf(this.maxPlayers)));
            }

            this.alivePlayers.remove(player);
            this.players.remove(player);

            if(this.plugin.getSpawnLocation() != null) {
                player.teleport(this.plugin.getSpawnLocation());
            }

            try {
                if(items.isSet("Items.Lobby.Games") && items.getBoolean("Items.Lobby.Games.Enable", true)) {
                    player.getInventory().setItem(items.getInt("Items.Lobby.Games.Slot", 0), ItemUtils.createItem(items.getString("Items.Lobby.Games.ID", "COMPASS"), items.getString("Items.Lobby.Games.Name", "&7Right click to open"), 1, items.getStringList("Items.Lobby.Games.Lore"), items.getInt("Items.Lobby.Games.Data")));
                }
            } catch (NullPointerException ex) {
                ex.printStackTrace();

                this.plugin.getLogger().log(Level.SEVERE, "The configuration for the Games item is wrong, please check your configuration or contact the developer!");
            }

            Team team = this.getPlayerTeam(player);
            if (team != null) {
                team.removeMember(player);
                if (this.state == ArenaState.PLAYING && !team.isAlive(this)) {
                    this.end();
                }
            }
        }
    }
    
    public void deathPlayer(Player player) {
        YamlConfig settings = this.plugin.getConfigManager().getSettings();
        YamlConfig messages = this.plugin.getConfigManager().getMessages();
        YamlConfig items = new YamlConfig(this.plugin, "Items");

        if (this.players.contains(player)) {
            this.alivePlayers.remove(player);

            player.getInventory().clear();
            try {
                if(items.isSet("Items.PreGame.Leave") && items.getBoolean("Items.PreGame.Leave.Enable", true)) {
                    player.getInventory().setItem(items.getInt("Items.PreGame.Leave.Slot", 8), ItemUtils.createItem(items.getString("Items.PreGame.Leave.ID", "RED_BED"), items.getString("Items.PreGame.Leave.Name", "&c&lLeave"), 1, items.getStringList("Items.PreGame.Leave.Lore"), items.getInt("Items.Pregame.Leave.Data")));
                }
            } catch (NullPointerException ex) {
                ex.printStackTrace();

                this.plugin.getLogger().log(Level.SEVERE, "The configuration for the Leave item is wrong, please check your configuration or contact the developer!");
            }

            this.sendMessage(messages.getString("Eliminated").replace("%player%", player.getName()));

            player.sendTitle(Utils.Color(messages.getString("Losses.Title")), Utils.Color(messages.getString("Losses.Sub")));

            this.addSpectator(player);

            Team team = this.getPlayerTeam(player);
            if (this.state == ArenaState.PLAYING && !team.isAlive(this)) {
                this.end();
            }
        }
    }
    
    public void clearMobs() {
        for (Entity entity : this.spawn.getWorld().getEntities()) {
            if (entity instanceof Animals || entity instanceof Monster || entity instanceof Item) {
                entity.remove();
            }
        }
    }
    
    public void addSpectator(Player player) {
        YamlConfig settings = this.plugin.getConfigManager().getSettings();
        YamlConfig messages = this.plugin.getConfigManager().getMessages();

        if (this.players.contains(player)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100000, 10000, true), true);
            player.setAllowFlight(true);
            player.setFlying(true);

            for (Player alive : this.alivePlayers) {
                alive.hidePlayer(player);
            }

            PlayerData playerData = this.plugin.getPlayerManager().getPlayerData(player);
            if (playerData != null) {
                playerData.setState(PlayerState.SPECTATING);
            }
            if (this.specs != null) {
                player.teleport(this.specs);
            } else {
                this.removePlayer(player, false);
            }
        }
    }
    
    public void sendMessage(String message) {
        for (Player player : this.players) {
            if (player.isOnline()) {
                player.sendMessage(Utils.Color(message));
            }
        }
    }
    
    public void end() {
        if(this.state != ArenaState.ENDING) {
            this.state = ArenaState.ENDING;

            YamlConfig messages = this.plugin.getConfigManager().getMessages();

            this.clearMobs();

            Team winner = null;

            for (Team team : teams) {
                if (team.isAlive(this)) {
                    winner = team;
                    break;
                }
            }

            for (String messageWin : messages.getStringList("TeamWinning")) {
                messageWin = messageWin.replaceAll("%team%", winner.getTeam().name());

                if (messageWin.contains("%members%")) {
                    StringBuilder stringBuilder = new StringBuilder();
                    for (Player player : winner.getMembers()) {
                        stringBuilder.append(player.getName()).append(" ");
                    }

                    messageWin = messageWin.replaceAll("%members%", stringBuilder.toString());
                }

                sendMessage(Utils.Color(messageWin));
            }

            for (Team team : this.teams) {
                for (Player player2 : team.getMembers()) {
                    if (!team.isAlive(this)) {
                        player2.sendTitle(Utils.Color(messages.getString("Losses.Title")), Utils.Color(messages.getString("Losses.Sub")));
                    } else {
                        player2.sendTitle(Utils.Color(messages.getString("Winning.Title")), Utils.Color(messages.getString("Winning.Sub")));
                    }
                }
            }

            if (winner != null) {
                FinishGameEvent event = new FinishGameEvent(winner, this);
                Bukkit.getPluginManager().callEvent(event);
            }

            new EndTask(this.plugin, this);
        }
    }

    public void forceEnd() {
        if(this.state != ArenaState.ENDING) {
            this.state = ArenaState.ENDING;

            this.clearMobs();

            if(!this.players.isEmpty()) {
                for (int i = 0; i < this.players.size(); i++) {
                    this.removePlayer(this.players.get(i), false);
                }
            }

            this.restart();
        }
    }
    
    public void start() {
        YamlConfig messages = this.plugin.getConfigManager().getMessages();

        this.sendMessage(messages.getString("GameStarted"));

        clearMobs();

        this.starting = false;
        this.started = true;
        this.state = ArenaState.PLAYING;
        this.startTask.cancel();
        this.startTask = null;

        Random rand = new Random();
        Team blue = this.getTeam(TeamColor.BLUE);
        Team red = this.getTeam(TeamColor.RED);

        boolean b = rand.nextBoolean();
        for (Player player : this.players) {
            if (this.getPlayerTeam(player) == null) {
                if (b) {
                    if (!blue.isMember(player)) {
                        red.addMember(player);
                    }
                    b = false;
                } else {
                    if (!red.isMember(player)) {
                        blue.addMember(player);
                    }
                    b = true;
                }
            }
        }

        for (Player player : this.players) {
            this.loadPlayer(player);
        }

        StartGameEvent event = new StartGameEvent(this);
        Bukkit.getPluginManager().callEvent(event);

        new GameTask(this.plugin, this);
    }
    
    public void restart() {
        for (Team team : this.teams) {
            team.resetTeam();
        }

        this.blockTracker.rollback();

        this.players.clear();
        this.alivePlayers.clear();
        this.state = ArenaState.WAITING;
        this.starting = false;
        this.started = false;
        this.force = false;
    }
    
    public Team getTeam(TeamColor teamColor) {
        for (Team team : this.teams) {
            if (team.getTeam() == teamColor) {
                return team;
            }
        }
        return null;
    }
    
    public Team getPlayerTeam(Player player) {
        for (Team team : this.teams) {
            if (team.isMember(player)) {
                return team;
            }
        }
        return null;
    }
    
    public void loadPlayer(Player player) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(Enchantment.ARROW_INFINITE, 1);

        ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
        chestplate.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 1);

        player.setFoodLevel(20);
        player.setHealth(player.getMaxHealth());
        player.setAllowFlight(false);
        player.setFlying(false);

        player.getInventory().clear();
        player.getInventory().addItem(bow, arrow);
        player.getInventory().setChestplate(chestplate);

        player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 100, 0, true, true));
        if (this.getPlayerTeam(player) != null) {
            player.teleport(this.getPlayerTeam(player).getLoc());
        }
    }
}

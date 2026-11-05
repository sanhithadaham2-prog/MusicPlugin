package com.example.musicplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;
import java.io.File;

public class SpotifyMusicPlugin extends JavaPlugin implements CommandExecutor, Listener {

    // Configure your credentials directly from the Spotify Developer Dashboard here
    private final String CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID";
    private final String CLIENT_SECRET = "YOUR_SPOTIFY_CLIENT_SECRET";
    private String accessToken = "";
    private RadioSongPlayer activeGlobalPlayer = null;

    @Override
    public void onEnable() {
        this.getCommand("music").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        File cacheDir = new File(getDataFolder(), "cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        getServer().getScheduler().runTaskAsynchronously(this, this::fetchSpotifyToken);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /music <song name by artist> OR /music stop");
            return true;
        }

        if (args[0].equalsIgnoreCase("stop")) {
            if (activeGlobalPlayer != null && activeGlobalPlayer.isPlaying()) {
                activeGlobalPlayer.setPlaying(false);
                activeGlobalPlayer.destroy();
                activeGlobalPlayer = null;
                Bukkit.broadcastMessage(ChatColor.RED + "🛑 Global music broadcast was stopped.");
            } else {
                sender.sendMessage(ChatColor.RED + "No global music is currently playing.");
            }
            return true;
        }

        String searchQuery = String.join(" ", args);
        Bukkit.broadcastMessage(ChatColor.GOLD + "📢 " + ChatColor.YELLOW + sender.getName() + ChatColor.GREEN + " requested global track: " + ChatColor.LIGHT_PURPLE + searchQuery);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String trackId = searchSpotifyTrack(searchQuery);
                if (trackId == null) {
                    sender.sendMessage(ChatColor.RED + "No matches found on Spotify.");
                    return;
                }

                File nbsFile = new File(getDataFolder(), "cache/" + trackId + ".nbs");
                
                if (!nbsFile.exists()) {
                    sender.sendMessage(ChatColor.RED + "Track found on Spotify, but no local .nbs file is cached under ID: " + trackId);
                    return;
                }

                getServer().getScheduler().runTask(this, () -> {
                    if (activeGlobalPlayer != null) {
                        activeGlobalPlayer.setPlaying(false);
                        activeGlobalPlayer.destroy();
                    }

                    Song song = NBSDecoder.parse(nbsFile);
                    activeGlobalPlayer = new RadioSongPlayer(song);
                    activeGlobalPlayer.setStereo(true);
                    
                    // Plays universally directly to all client headers (like an End Portal opening)
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        activeGlobalPlayer.addPlayer(onlinePlayer);
                    }
                    
                    activeGlobalPlayer.setPlaying(true);
                    Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "🎵 Now playing globally: " + ChatColor.AQUA + song.getTitle());
                });

            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "An network or validation error occurred.");
                e.printStackTrace();
            }
        });

        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (activeGlobalPlayer != null && activeGlobalPlayer.isPlaying()) {
            activeGlobalPlayer.addPlayer(event.getPlayer());
        }
    }

    private void fetchSpotifyToken() {
        try {
            URL url = new URL("https://spotify.com");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            
            String basicAuth = java.util.Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes());
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);

            String data = "grant_type=client_credentials";
            conn.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                
                String resStr = response.toString();
                this.accessToken = resStr.split("\"access_token\":\"")[1].split("\"")[0];
                getLogger().info("Successfully authenticated with Spotify API.");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to authenticate with Spotify API: " + e.getMessage());
        }
    }

    private String searchSpotifyTrack(String query) throws Exception {
        if (accessToken.isEmpty()) return null;

        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://spotify.com" + encodedQuery + "&type=track&limit=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);

        if (conn.getResponseCode() == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            String json = response.toString();
            if (json.contains("\"id\":\"")) {
                return json.split("\"id\":\"")[1].split("\"")[0];
            }
        }
        return null;
    }
}

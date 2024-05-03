package anhc;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import com.xpdustry.nohorny.NoHornyAPI;
import com.xpdustry.nohorny.NoHornyCache;
import com.xpdustry.nohorny.NoHornyImage;
import com.xpdustry.nohorny.analyzer.ImageAnalyzer;
import com.xpdustry.nohorny.analyzer.ImageAnalyzerEvent;
import com.xpdustry.nohorny.analyzer.ImageAnalyzer.Rating;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

import com.xpdustry.nohorny.geometry.Cluster;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.Schematic.Stile;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.mod.Plugin;
import mindustry.net.Administration;

public class NHConfigurator extends Plugin {
    static final String WarningsSettingsName = "nhc-warnings";
    static final String BadCacheSettingsName = "nhc-bad-cache";
    private static final DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");
    private static final StringMap cache = new StringMap();
    private static StringMap badCache = new StringMap();
    static StringMap warnings = new StringMap();
    private static Fi currentLogFile = null;
    private final Fi logFolder = Core.settings.getDataDirectory().child("nhc-logs/");;
    private final Fi imageFolder = logFolder.child("images/");
    private final Fi schematicFolder = logFolder.child("schematics/");

    private static long getImageID(BufferedImage image) {
        long compression = 10_000L;
        if (image.getWidth() < 176 || image.getHeight() < 176) compression /= 32;

        long id = 0L;

        for(int x = 0; x < image.getWidth(); ++x) {
            for(int y = 0; y < image.getHeight(); ++y) {
                Color c = new Color(image.getRGB(x, y));
                id += c.getRed();
                id += c.getGreen();
                id += c.getBlue();
            }
        }

        return id / compression;
    }

    public void init() {
        warnings = Core.settings.getJson(WarningsSettingsName, StringMap.class, StringMap::new);
        badCache = Core.settings.getJson(BadCacheSettingsName, StringMap.class, StringMap::new);

        for (Fi f : schematicFolder.findAll())
            cache.put(f.name().split("\\.")[0], f.name());

        logFolder.mkdirs();
        imageFolder.mkdirs();
        schematicFolder.mkdirs();

        if (Config.cache.bool()) NoHornyAPI.get().setCache(new NHCache());

        Events.on(ImageAnalyzerEvent.class, (event) -> {
            if (event.getResult().getRating() != Rating.UNSAFE || event.getAuthor() == null) return;

            String uuid = event.getAuthor().getUuid();
            String nsfwID = Long.toString(Time.millis());

            if (Config.saveEvidence.bool()) {
                long id = getImageID(event.getImage());
                if (!cache.get(Long.toString(id)).isEmpty()) {
                    Log.debug("@ already saved!", id);
                } else {
                    String fileName = id + ".msch";
                    cache.put(Long.toString(id), fileName);
                    nsfwID = fileName;

                    Seq<Stile> tiles = new Seq<>();
                    for (final var block : event.getCluster().getBlocks()) {
                        if (block.getPayload() instanceof NoHornyImage.Display display) {
                            for (final var entry : display.getProcessors().entrySet()) {
                                final var point = entry.getKey();
                                Building b = Vars.world.build(point.getX(), point.getY());
                                if (b != null)
                                    tiles.add(new Stile(b.block(), b.tileX(), b.tileY(), b.config(), (byte) b.rotation));
                            }
                        }
                        Building b = Vars.world.build(block.getX(), block.getY());
                        if (b != null)
                            tiles.add(new Stile(b.block(), b.tileX(), b.tileY(), b.config(), (byte) b.rotation));
                    }

                    Schematic schem = new Schematic(tiles, new StringMap(), Vars.maxSchematicSize, Vars.maxSchematicSize);

                    Fi f;
                    try {
                        f = schematicFolder.child(fileName);
                        Schematics.write(schem, f);
                        Log.debug("Saved schem @", f.name());
                    } catch (IOException e) {
                        Log.err(e);
                        return;
                    }

                    try {
                        f = imageFolder.child(id + ".png");
                        f.mkdirs();
                        ImageIO.write(event.getImage(), "png", f.file());
                        Log.debug("Saved image @", f.name());
                    } catch (IOException e) {
                        Log.err(e);
                    }
                }
            }

            if (Config.delete.bool()) {
                Player p = Groups.player.find((p1) -> p1.uuid().equals(uuid));
                Unit u;
                if (p == null) {
                    if (Groups.player.isEmpty()) {
                        u = Groups.unit.first();
                    } else {
                        u = Groups.player.first().unit();
                    }
                } else {
                    u = p.unit();
                }

                for (final var block : event.getCluster().getBlocks()) {
                    if (block.getPayload() instanceof NoHornyImage.Display display) {
                        for (final var entry : display.getProcessors().entrySet()) {
                            final var point = entry.getKey();
                            var t = Vars.world.tile(point.getX(), point.getY());
                            Call.deconstructFinish(t, t.block(), u);
                        }
                    }
                    var t = Vars.world.tile(block.getX(), block.getY());
                    Call.deconstructFinish(t, t.block(), u);
                }
            }

            int w = Strings.parseInt(warnings.get(uuid, "0")) + 1;
            warnings.put(uuid, Integer.toString(w));
            Core.settings.putJson(WarningsSettingsName, warnings);
            Core.settings.forceSave();

            if (w > Config.maxWarnings.num()) {
                switch (Config.action.num()) {
                    case 1:
                        Vars.netServer.admins.banPlayer(uuid);
                        logToFile("Banned uuid @ for placing nsfw @", uuid, nsfwID);
                        break;
                    case 2:
                        int multiplier = w - Config.maxWarnings.num();
                        long duration = (long) Config.kickDuration.num() * 60000L * (long) (Config.irop.bool() ? multiplier : 1);
                        String ip = Objects.requireNonNull(event.getAuthor()).getAddress().getHostAddress();

                        for (var p : Groups.player) {
                            if (!p.uuid().equals(uuid) && !p.ip().equals(ip)) {
                                p.kick("[scarlet]Placing NSFW", duration);
                                logToFile("Kicked @ (@, @) for @ hours for placing nsfw @", p.name, p.uuid(), p.ip(), duration / 3600000L, nsfwID);
                            }
                        }
                }
            } else {
                Player p = Groups.player.find((p1) -> p1.uuid().equals(uuid));
                if (p != null)
                    Call.infoMessage(p.con, "[scarlet]NSFW []= \ue817[scarlet]!");
            }

        });
    }

    public void registerServerCommands(CommandHandler handler) {
        handler.register("nh-config", "[name] [value...]", "Configure nohorny settings.", (arg) -> {
            if (arg.length == 0) {
                Log.info("All config values:");

                for (Config cx : Config.all) {
                    Log.info("&lk| @: @", cx.name, "&lc&fi" + cx.get());
                    Log.info("&lk| | &lw" + cx.description);
                    Log.info("&lk|");
                }
            } else {
                Config c = Config.all.find((conf) -> conf.name.equalsIgnoreCase(arg[0]));
                if (c != null) {
                    if (arg.length == 1) {
                        Log.info("'@' is currently @.", c.name, c.get());
                    } else {
                        if (arg[1].equals("default")) {
                            c.set(c.defaultValue);
                        } else if (!c.isBool()) {
                            if (c.isNum()) {
                                try {
                                    c.set(Integer.parseInt(arg[1]));
                                } catch (NumberFormatException ignored) {
                                    Log.err("Not a valid number: @", arg[1]);
                                    return;
                                }
                            } else if (c.isString()) {
                                c.set(arg[1].replace("\\n", "\n"));
                            }
                        } else {
                            c.set(arg[1].equals("on") || arg[1].equals("true"));
                        }

                        Log.info("@ set to @.", c.name, c.get());
                        Core.settings.forceSave();
                    }
                } else {
                    Log.err("Unknown config: '@'. Run the command with no arguments to get a list of valid configs.", arg[0]);
                }

            }
        });
        handler.register("nh-clear-cache", "clears cache for no horny config", (args) -> {
            warnings.clear();
            badCache.clear();
            Core.settings.remove(WarningsSettingsName);
            Core.settings.remove(BadCacheSettingsName);
            Core.settings.forceSave();
            Log.info("Done.");
        });
    }

    public void logToFile(String text, Object... args) {
        Log.info(text, args);
        text = Log.formatColors(text, false, args);
        if (currentLogFile != null && currentLogFile.length() > (long)Administration.Config.maxLogLength.num()) {
            currentLogFile.writeString("[End of log file. Date: " + dateTime.format(LocalDateTime.now()) + "]\n", true);
            currentLogFile = null;
        }

        if (currentLogFile == null) {
            int i = 0;
            while(logFolder.child("log-" + i + ".txt").length() >= Administration.Config.maxLogLength.num()){
                i++;
            }

            currentLogFile = logFolder.child("log-" + i + ".txt");
        }

        currentLogFile.writeString(text + "\n", true);
    }

    public static class NHCache implements NoHornyCache {
        @Override
        public CompletableFuture<ImageAnalyzer.Result> getResult(Cluster<? extends NoHornyImage> cluster, BufferedImage bufferedImage) {
            Log.debug("cache start");
            var id = Long.toString(getImageID(bufferedImage));
            var bad = badCache.get(id);
            if (bad != null) {
                Log.debug("@ is cached!", id);
                return CompletableFuture.completedFuture(new ImageAnalyzer.Result(bad.equals("nsfw") ? Rating.UNSAFE : Rating.SAFE, new HashMap<>()));
            } else {
                Log.debug("@ is NOT cached!", id);
                return CompletableFuture.completedFuture(null);
            }
        }

        @Override
        public void putResult(Cluster<? extends NoHornyImage> cluster, BufferedImage bufferedImage, ImageAnalyzer.Result result) {
            var id = Long.toString(getImageID(bufferedImage));
            var bad = result.getRating() == Rating.UNSAFE;
            badCache.put(id, bad ? "nsfw" : "sfw");
            Core.settings.putJson(BadCacheSettingsName, badCache);
            Core.settings.forceSave();
            Log.debug("Added @ as @ to NoHorny cache", id, bad ? "nsfw" : "sfw");
        }
    }
}

package anhc;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;

public class Config {
   public static final Seq<Config> all = new Seq<>();

   public static final Config maxWarnings = new Config("warnings", "how many warnings to give before taking action", 2, null, () -> {
      NHConfigurator.warnings.clear();
      Core.settings.remove("nhc-warnings");
      Core.settings.forceSave();
   });
   public static final Config action = new Config("action", "Upon detecting nsfw: 1) ban 2) kick 3) ignore", 2);
   public static final Config kickDuration = new Config("kickDuration", "Kick duration for nsfw detection, in minutes", 60);
   public static final Config saveEvidence = new Config("saveEvidence", "save nsfw evidence", false);
   public static final Config irop = new Config("irop", "Increase Repeat Offender's Punishment. Increases kick length for repeat offenders.", true);
   public static final Config delete = new Config("delete", "Delete the nsfw when detected (Why would you turn this off?)", true);
   public static final Config cache = new Config("cache", "Cache API Results", true, () -> Log.warn("Restart the server for this change to take effect."));

   public final Object defaultValue;
   public final String name;
   public final String key;
   public final String description;
   final Runnable changed;

   public Config(String name, String description, Object def) {
      this(name, description, def, null, null);
   }

   public Config(String name, String description, Object def, String key) {
      this(name, description, def, key, null);
   }

   public Config(String name, String description, Object def, Runnable changed) {
      this(name, description, def, null, changed);
   }

   public Config(String name, String description, Object def, String key, Runnable changed) {
      this.name = name;
      this.description = description;
      this.key = "anhc." + (key == null ? name : key);
      this.defaultValue = def;
      this.changed = changed == null ? () -> {
      } : changed;
      all.add(this);
   }

   public boolean isNum() {
      return this.defaultValue instanceof Integer;
   }

   public boolean isBool() {
      return this.defaultValue instanceof Boolean;
   }

   public boolean isString() {
      return this.defaultValue instanceof String;
   }

   public Object get() {
      return Core.settings.get(this.key, this.defaultValue);
   }

   public boolean bool() {
      return Core.settings.getBool(this.key, (Boolean)this.defaultValue);
   }

   public int num() {
      return Core.settings.getInt(this.key, (Integer)this.defaultValue);
   }

   public String string() {
      return Core.settings.getString(this.key, (String)this.defaultValue);
   }

   public void set(Object value) {
      Core.settings.put(this.key, value);
      this.changed.run();
   }
}


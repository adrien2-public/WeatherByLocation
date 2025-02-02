package mc.spigot.weatherbylocation;

import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.yaml.snakeyaml.parser.ParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeatherByLocation extends JavaPlugin {
    // Constants
    public enum WeatherType {
        CLEAR,
        RAIN,
        THUNDERSTORM
    }

    Map<Integer, String> WmoCodes = new HashMap<Integer, String>() {{
        put(0, "Clear");
        put(1, "Mainly Clear");
        put(2, "Partly Cloudy");
        put(3, "Overcast");
        put(45, "Fog");
        put(48, "Heavy Fog");
        put(51, "Light Drizzle");
        put(53, "Moderate Drizzle");
        put(55, "Heavy Drizzle");
        put(56, "Light Freezing Drizzle");
        put(57, "Heavy Freezing Drizzle");
        put(61, "Light Rain");
        put(63, "Moderate Rain");
        put(65, "Heavy Rain");
        put(71, "Light Snow");
        put(73, "Moderate Snow");
        put(75, "Heavy Snow");
        put(77, "Snow Grains");
        put(80, "Light Rain Showers");
        put(81, "Moderate Rain Showers");
        put(82, "Violent Rain Showers");
        put(85, "Snow Showers");
        put(86, "Heavy Snow Showers");
        put(95, "Thunderstorms");
        put(96, "Thunderstorm with Light Hail");
        put(99, "Thunderstorm with Heavy Hail");
    }};

    List<Integer> clearWeatherCodes = Arrays.asList(0, 1, 2, 3);
    List<Integer> rainWeatherCodes = Arrays.asList(45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80,
            81, 82, 85, 86);
    List<Integer> thunderstormWeatherCodes = Arrays.asList(95, 96, 99);
    BukkitTask updateWeatherTask;
    // Config
    ServerLocator.LocationData locationData;
    int minutesBetweenUpdates;
    boolean shutdown = false;

    @Override
    public void onEnable() {
        getLogger().info("WeatherByLocation was enabled.");
        getCommand("set-weather-location").setExecutor(new SetLocationCommand(this));
        getCommand("get-weather-location").setExecutor(new GetLocationCommand(this));
        getCommand("set-weather-update-interval").setExecutor(new SetUpdateInterval(this));

        runStartupTasks();
    }
    @Override
    public void onDisable() {
        shutdown = true;
        getLogger().info("Stopping background tasks...");
        updateWeatherTask.cancel();
        getLogger().info("WeatherByLocation was disabled.");
    }

    public ServerLocator.LocationData getLocationData() {
        return locationData;
    }

    public void loadLocationDataFromConfig() {
        locationData.latitude = getConfig().getDouble("latitude");
        locationData.longitude = getConfig().getDouble("longitude");
        if (getConfig().contains("city")) {
            locationData.city = getConfig().getString("city");
        }
        if (getConfig().contains("region")) {
            locationData.region = getConfig().getString("region");
        }
        if (getConfig().contains("countryCode")) {
            locationData.countryCode = getConfig().getString("countryCode");
        }
    }

    private void runStartupTasks() {
        // Create default configuration file, if it doesn't exist
        saveDefaultConfig();

        // validate config.yml file
        boolean configIsValid = validateConfigFile();
        if (!configIsValid ) return;

        // Refresh config
        reloadConfig();
        // Load location data
        loadLocation();
        // Schedule task to run every minutesBetweenUpdates minutes
        minutesBetweenUpdates = getConfig().getInt("minutes-between-updates");
        BukkitScheduler scheduler = getServer().getScheduler();
        updateWeatherTask = scheduler.runTaskTimerAsynchronously(this, () -> {
            try {
                // Get latest values from config, if they exist
                reloadConfig();
                loadLocationDataFromConfig();
                // Fetch weather data
                WeatherType weatherType = getCurrentWeather(locationData.latitude, locationData.longitude);
                // Update weather on server
                if (!shutdown) {
                    scheduler.runTask(this, () -> {
                        setWeather(weatherType);
                    });
                }
            } catch (IOException | InterruptedException e) {
                getLogger().warning("Error fetching weather data.");
                getLogger().warning(e.toString());
            }
        }, 0L, (20L * 60 * minutesBetweenUpdates));

    }

    private void loadLocation() {
        // Check for manually set location
        if (configHasManualLocation()) {
            locationData = new ServerLocator.LocationData();
            loadLocationDataFromConfig();
            getLogger().info(String.format("Loaded manually set location from config: (%.3f, %.3f)",
                    locationData.latitude, locationData.longitude));
        }
        // Check for cached location. If it exists, also verify the cached IP address matches the server's current IP address.
        else if (configHasCachedLocation() && ServerLocator.isCurrentIpAddress(getConfig().getString("ipAddress"))) {
            locationData = new ServerLocator.LocationData();
            loadLocationDataFromConfig();
            getLogger().info(String.format("Loaded cached location: %s, %s, %s at (%.3f, %.3f)", locationData.city,
                    locationData.region, locationData.countryCode, locationData.latitude, locationData.longitude));
        }
        // Otherwise, we need to geolocate the server and get location data from it
        else {
            ServerLocator serverLocator = new ServerLocator(this);
            try {
                locationData = serverLocator.locate();
            } catch (IOException | InterruptedException e) {
                getLogger().warning("Error fetching weather data.");
                getLogger().warning(e.toString());
            }
        }
    }

    private boolean configHasManualLocation() {
        return getConfig().contains("latitude") && getConfig().contains("longitude") && !getConfig().contains("ipAddress");
    }

    private boolean configHasCachedLocation() {
        return getConfig().contains("latitude") && getConfig().contains("longitude") && getConfig().contains("ipAddress");
    }

    private void setWeather(WeatherType weatherType) {
        /*
        NOTE: This function should only be called in a synchronous task.
         */
        World world = Bukkit.getWorlds().get(0);
        // If no change to the weather type, extend weather duration and return
        if (weatherType == getCurrentServerWeather()) {
            world.setWeatherDuration(20 * 60 * minutesBetweenUpdates);
            return;
        }
        else if (weatherType.equals(WeatherType.CLEAR)) {
            world.setStorm(false);
            world.setThundering(false);
            getLogger().info("Weather was set to Clear.");
        }
        else if (weatherType.equals(WeatherByLocation.WeatherType.RAIN)) {
            world.setStorm(true);
            world.setThundering(false);
            getLogger().info("Weather was set to Rain.");
        }
        else if (weatherType.equals(WeatherByLocation.WeatherType.THUNDERSTORM)) {
            world.setStorm(true);
            world.setThundering(true);
            getLogger().info("Weather was set to Thunderstorm.");
        }
        world.setWeatherDuration(20 * 60 * minutesBetweenUpdates);
    }

    private WeatherType getCurrentWeather(double lat, double lon) throws IOException, InterruptedException {
        // Send request to Open Meteo API
        int weatherCode = WeatherRequest.getCurrentWeather(lat, lon);
        getLogger().info(String.format("Current weather for (%.3f, %.3f) is %s. Weather data by Open-Metro.com",
                lat, lon, WmoCodes.get(weatherCode)));
        if (rainWeatherCodes.contains(weatherCode)) {
            return WeatherType.RAIN;
        }
        else if (thunderstormWeatherCodes.contains(weatherCode)) {
            return WeatherType.THUNDERSTORM;
        }
        else {
            return WeatherType.CLEAR;
        }
    }

    private WeatherType getCurrentServerWeather() {
        World world = Bukkit.getWorlds().get(0);
        if (world.hasStorm() && world.isThundering()) {
            return WeatherType.THUNDERSTORM;
        }
        else if (world.hasStorm()) {
            return WeatherType.RAIN;
        }
        else {
            return WeatherType.CLEAR;
        }
    }

    private boolean validateConfigFile() {
        File dataFolder = getDataFolder();
        // Create a File object for the config.yml file
        File configFile = new File(dataFolder, "config.yml");
        var ans= loadConfiguration(configFile);
        if(ans == null) {
            getLogger().warning( "An configuration exception occurred. Server will shut down in 15 seconds.");
            try {
                Thread.sleep(15000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            BukkitScheduler scheduler = getServer().getScheduler();
            updateWeatherTask = scheduler.runTaskTimerAsynchronously(this, () -> {
            }, 0L, (20L * 60 * minutesBetweenUpdates));

            Bukkit.shutdown();
            return false;
        }
        return true ;
    }



    public YamlConfiguration loadConfiguration(File file) {

        if (file == null) {
            // File is null
            getLogger().warning( "Config File is found to be null, please make sure config file is valid");
            return null;
        }

        YamlConfiguration config = new YamlConfiguration();

        try {
            config.load(file);
        } catch (FileNotFoundException ex) {
            getLogger().warning( "Config File is not found , please ensure that file exists");
            return null;
        } catch (IOException ex) {
            getLogger().warning( "IO Exception was found in the loading of config file, please review for any issues");
            return null;
        } catch (ParserException ex) {
            getLogger().warning( "Config File is found to have issues with parsing, possibly the syntax of the config.yml, please review contents");
            return null;
        }catch (InvalidConfigurationException ex) {
            getLogger().warning( "Config File is found to have invalid configuration, please review contents");
            return null;
        }

        return config;
    }

}

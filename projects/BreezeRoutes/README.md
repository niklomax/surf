# Breeze Routes

Trying to compare routes from Breeze data and those produced by Google Maps. This README gives the main steps needed to re-do the work.

## Required OSM Data

Open Street Map data are required to match to the road network. 

I've not added OSM data to the repository. You need to downloaded `*.osm.pbf` files and store them in the `map-data` directory. [GeoFabrik](http://download.geofabrik.de) has lots of OSM data.

E.g. the following should work for Massachusetts data:

```
cd map-data
wget http://download.geofabrik.de/north-america/us/massachusetts-latest.osm.pbf 
```

## Required Libraries

All the required libraries are in the ./lib/ directory. 

The main third-party libraries are [Graphhopper](https://github.com/graphhopper/graphhopper) (for routing) and [map-matching](https://github.com/graphhopper/map-matching) for building a route (list of OSM street segments) from the GPS data.

## 1. Preparing GPX files with json2gpx

To assign the GPS coordiantes to OSM segments I'm using the [map-matching](https://github.com/graphhopper/map-matching) library. This requires GPX files, rather than geojson files. Converting geojson to gpx should be easy, but the times associated with each coordinate are stored in a non-standard place in the json:

```
features:properties:coordinateProperties:times
```

So I've used the [togpx](https://github.com/tyrasd/togpx) library to convert the geojson files to GPX files. The only tricky thing is using a callback function that specifies where the times associated with each coordinate are in the json.

For details see: `breezetogpx.js`. The script reads all `.json` files in a directory and writes out `.gpx` files to a specified directory.

To run the script do:

```
./breezetogpx.js <input directory> <output directory>
```

**XXXX Extend script to run over all files in a specified directory**

## 2. Map Matching with GraphHopper

**The `BreezeRoutes` directory is the root of an IntelliJ IDEA project. So the easiest way edit or run the code is through IntelliJ IDEA. (There is a free version of the IDE.**

The `surf.projects.breezeroutes.MapMatcher` class does most of the work. It looks for GPX files in the `traces` directory (the output files from step 1), matches them to the OSM network, and then XXXX (what to do with output?).
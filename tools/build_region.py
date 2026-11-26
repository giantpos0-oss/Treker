#!/usr/bin/env python3
"""
Builds Treker's offline street database for one region from an OpenStreetMap .osm.pbf extract.

    python3 build_region.py region.osm.pbf out.db --id illinois --name "Illinois"

The database holds everything Treker needs offline except routing (Valhalla tiles are built
separately):
  streets        named streets and paths, geometry as packed int32 lat/lon/junction triples
  street_cells   which ~1 km cells each street passes through, for fast lookups around a point
  junctions      every node shared by two or more named streets, with the street names there
  pois           named places (shops, restaurants, stops, landmarks...) with a spoken kind
  addresses      house numbers with street, city and zip code
  areas          country, state, county, city, neighborhood and zip polygons for "where am I"
  meta           region id, name, bounding box, build date

Map data © OpenStreetMap contributors, ODbL.
"""
import argparse
import datetime
import sqlite3
import struct
import sys

import numpy as np
import osmium
from shapely.geometry import MultiPolygon, Polygon, box, shape

CELL = 0.01  # degrees, about 1.1 km north-south


def cell_of(lat, lon):
    return int((lat + 90) / CELL) * 100000 + int((lon + 180) / CELL)


SKIP_HIGHWAY = {"proposed", "construction", "abandoned", "platform", "raceway", "bus_guideway",
                "elevator", "razed", "disused", "no", "via_ferrata"}


def street_name(tags):
    name = tags.get("name") or tags.get("ref")
    hw = tags.get("highway")
    if not hw or hw in SKIP_HIGHWAY or tags.get("area") == "yes":
        return None
    return name


# ---- What a place is, in words a person would say ----

POI_KEYS = ["amenity", "shop", "tourism", "leisure", "office", "healthcare", "craft", "historic",
            "public_transport", "railway", "aeroway", "man_made", "emergency", "sport", "club", "building"]

SPECIAL_KINDS = {
    ("amenity", "fast_food"): "Fast food",
    ("amenity", "fuel"): "Gas station",
    ("amenity", "pharmacy"): "Pharmacy",
    ("amenity", "place_of_worship"): "Place of worship",
    ("amenity", "bus_station"): "Bus station",
    ("amenity", "parking"): "Parking",
    ("amenity", "toilets"): "Restrooms",
    ("amenity", "atm"): "ATM",
    ("shop", "supermarket"): "Supermarket",
    ("shop", "convenience"): "Convenience store",
    ("shop", "department_store"): "Department store",
    ("shop", "mall"): "Shopping mall",
    ("highway", "bus_stop"): "Bus stop",
    ("public_transport", "platform"): "Transit stop",
    ("public_transport", "station"): "Transit station",
    ("public_transport", "stop_position"): None,
    ("railway", "station"): "Train station",
    ("railway", "halt"): "Train station",
    ("railway", "tram_stop"): "Tram stop",
    ("railway", "subway_entrance"): "Subway entrance",
    ("aeroway", "aerodrome"): "Airport",
    ("tourism", "hotel"): "Hotel",
    ("leisure", "park"): "Park",
}

SKIP_VALUES = {"yes", "no", "parking_space", "parking_entrance", "bench", "waste_basket", "bicycle_parking",
               "vending_machine", "grit_bin", "house", "residential", "detached", "garage", "garages",
               "apartments", "shed", "roof", "terrace", "semidetached_house", "hut", "boundary_stone",
               "survey_point", "pole", "street_lamp", "manhole", "utility_pole", "pipeline", "tower",
               "mast", "storage_tank", "silo", "chimney", "water_tower", "flagpole", "clock", "cairn"}


def poi_kind(tags):
    if tags.get("highway") == "bus_stop":
        return "Bus stop"
    for k in POI_KEYS:
        v = tags.get(k)
        if not v:
            continue
        if (k, v) in SPECIAL_KINDS:
            return SPECIAL_KINDS[(k, v)]
        if v in SKIP_VALUES:
            if k == "building":
                return None
            continue
        words = v.replace("_", " ").replace(";", ", ")
        if k == "shop":
            return words.capitalize() + " shop"
        if k == "office":
            return words.capitalize() + " office"
        if k == "building":
            return words.capitalize() + " building"
        return words.capitalize()
    return None


class Pass1(osmium.SimpleHandler):
    """Counts how many named-street ways use each node, to find junctions."""

    def __init__(self):
        super().__init__()
        self.chunks = []
        self.buf = []

    def way(self, w):
        if street_name(w.tags) is None:
            return
        # Each node counted once per way, so a looping way doesn't make false junctions.
        self.buf.extend(set(n.ref for n in w.nodes))
        if len(self.buf) > 1_000_000:
            self.chunks.append(np.array(self.buf, dtype=np.int64))
            self.buf = []

    def junction_nodes(self):
        if self.buf:
            self.chunks.append(np.array(self.buf, dtype=np.int64))
        if not self.chunks:
            return np.array([], dtype=np.int64)
        all_refs = np.concatenate(self.chunks)
        uniq, counts = np.unique(all_refs, return_counts=True)
        return uniq[counts >= 2]


class Pass2(osmium.SimpleHandler):
    def __init__(self, db, junction_ids):
        super().__init__()
        self.db = db
        # node id -> junction index
        self.junction_index = {int(n): i for i, n in enumerate(junction_ids)}
        self.junction_pos = [None] * len(junction_ids)
        self.junction_names = [set() for _ in junction_ids]
        self.streets = []
        self.street_cells = []
        self.pois = []
        self.addresses = []
        self.areas = []
        self.place_nodes = []
        self.bbox = [90.0, 180.0, -90.0, -180.0]
        self.next_street = 1
        self.next_poi = 1

    def flush(self, force=False):
        c = self.db
        if force or len(self.streets) > 20000:
            c.executemany("INSERT INTO streets VALUES (?,?,?,?)", self.streets)
            c.executemany("INSERT INTO street_cells VALUES (?,?)", self.street_cells)
            self.streets.clear(); self.street_cells.clear()
        if force or len(self.pois) > 20000:
            c.executemany("INSERT INTO pois VALUES (?,?,?,?,?,?,?,?)", self.pois)
            self.pois.clear()
        if force or len(self.addresses) > 20000:
            c.executemany("INSERT INTO addresses VALUES (?,?,?,?,?,?,?)", self.addresses)
            self.addresses.clear()

    def grow(self, lat, lon):
        b = self.bbox
        b[0] = min(b[0], lat); b[1] = min(b[1], lon); b[2] = max(b[2], lat); b[3] = max(b[3], lon)

    def add_poi_or_address(self, tags, lat, lon, osm=""):
        name = tags.get("name")
        if name:
            kind = poi_kind(tags)
            if kind:
                street = tags.get("addr:street", "")
                num = tags.get("addr:housenumber", "")
                addr = f"{num} {street}".strip()
                self.pois.append((self.next_poi, name, kind, lat, lon, cell_of(lat, lon), addr, osm))
                self.next_poi += 1
        num = tags.get("addr:housenumber")
        street = tags.get("addr:street")
        if num and street:
            self.addresses.append((num, street, tags.get("addr:city", ""), tags.get("addr:postcode", ""),
                                   lat, lon, cell_of(lat, lon)))
        self.flush()

    def node(self, n):
        if not n.location.valid():
            return
        lat, lon = n.location.lat, n.location.lon
        self.grow(lat, lon)
        idx = self.junction_index.get(n.id)
        if idx is not None:
            self.junction_pos[idx] = (lat, lon)
        tags = n.tags
        if len(tags) == 0:
            return
        place = tags.get("place")
        if place in ("neighbourhood", "suburb", "quarter") and tags.get("name"):
            self.place_nodes.append(("neighborhood", tags.get("name"), lat, lon))
        self.add_poi_or_address(tags, lat, lon, f"node/{n.id}")

    def way(self, w):
        name = street_name(w.tags)
        if name is None:
            return
        try:
            pts = [(n.lat, n.lon, n.ref) for n in w.nodes]
        except osmium.InvalidLocationError:
            pts = [(n.lat, n.lon, n.ref) for n in w.nodes if n.location.valid()]
        if len(pts) < 2:
            return
        sid = self.next_street
        self.next_street += 1
        blob = bytearray()
        cells = set()
        for lat, lon, ref in pts:
            j = self.junction_index.get(ref, -1)
            if j >= 0:
                self.junction_names[j].add(name)
            blob += struct.pack("<iii", round(lat * 1e6), round(lon * 1e6), j)
            cells.add(cell_of(lat, lon))
        self.streets.append((sid, name, w.tags.get("highway"), bytes(blob)))
        self.street_cells.extend((c, sid) for c in cells)
        self.flush()

    def area(self, a):
        tags = a.tags
        level = None
        name = tags.get("name")
        boundary = tags.get("boundary")
        if boundary == "administrative":
            level = {"2": "country", "4": "state", "6": "county", "7": "city", "8": "city",
                     "9": "neighborhood", "10": "neighborhood"}.get(tags.get("admin_level"))
        elif boundary == "postal_code" and tags.get("postal_code"):
            level, name = "zip", tags.get("postal_code")
        elif tags.get("place") in ("city", "town", "village", "hamlet") and name:
            level = "city"
        elif tags.get("place") in ("neighbourhood", "suburb", "quarter") and name:
            level = "neighborhood"
        if level and name:
            self.add_area(level, name, a)
            return
        # A place mapped as a building or area: use its middle.
        if name and poi_kind(tags) or (tags.get("addr:housenumber") and tags.get("addr:street")):
            try:
                ring = next(iter(a.outer_rings()))
                pts = list(ring)[:-1] or list(ring)
                lats = [n.lat for n in pts]; lons = [n.lon for n in pts]
            except (StopIteration, osmium.InvalidLocationError):
                return
            if lats:
                osm = f"way/{a.orig_id()}" if a.from_way() else f"relation/{a.orig_id()}"
                self.add_poi_or_address(tags, sum(lats) / len(lats), sum(lons) / len(lons), osm)

    def add_area(self, level, name, a):
        polys = []
        try:
            for outer in a.outer_rings():
                shell = [(n.lon, n.lat) for n in outer]
                holes = [[(n.lon, n.lat) for n in inner] for inner in a.inner_rings(outer)]
                if len(shell) >= 4:
                    polys.append(Polygon(shell, [h for h in holes if len(h) >= 4]))
        except osmium.InvalidLocationError:
            return
        if not polys:
            return
        geom = MultiPolygon(polys) if len(polys) > 1 else polys[0]
        # Simplify: about 20 m accuracy is plenty for "which city am I in".
        geom = geom.simplify(0.0002, preserve_topology=True)
        if geom.is_empty:
            return
        self.areas.append((level, name, geom))


def pack_rings(geom):
    """All rings, outer and inner: count, then for each ring its point count and int32 lat/lon pairs.
    Point-in-polygon with the even-odd rule over every ring gives the right answer for holes."""
    polys = list(geom.geoms) if geom.geom_type == "MultiPolygon" else [geom]
    rings = []
    for p in polys:
        rings.append(p.exterior.coords)
        rings.extend(i.coords for i in p.interiors)
    out = bytearray(struct.pack("<i", len(rings)))
    for r in rings:
        pts = list(r)
        out += struct.pack("<i", len(pts))
        for lon, lat in pts:
            out += struct.pack("<ii", round(lat * 1e6), round(lon * 1e6))
    return bytes(out)


def zcta_areas(path, bbox):
    """Zip code tabulation areas from the Census Bureau that overlap this region."""
    import shapefile  # pyshp
    region = box(bbox[1], bbox[0], bbox[3], bbox[2])
    reader = shapefile.Reader(path)
    fields = [f[0] for f in reader.fields[1:]]
    key = next(f for f in fields if f.upper().startswith("ZCTA5CE"))
    n = 0
    for sr in reader.iterShapeRecords():
        x0, y0, x1, y1 = sr.shape.bbox
        if x1 < bbox[1] or x0 > bbox[3] or y1 < bbox[0] or y0 > bbox[2]:
            continue
        geom = shape(sr.shape.__geo_interface__)
        if not geom.intersects(region):
            continue
        # About 5 m accuracy: zip edges often run down the middle of a street.
        geom = geom.simplify(0.00005, preserve_topology=True)
        n += 1
        yield ("zip", sr.record[fields.index(key)], geom)
    print(f"  {n} zip code areas", file=sys.stderr)


def census_areas(path, bbox, level):
    """Census Bureau boundaries (public domain) of one kind that overlap this region."""
    import shapefile  # pyshp
    region = box(bbox[1], bbox[0], bbox[3], bbox[2])
    reader = shapefile.Reader(path)
    fields = [f[0] for f in reader.fields[1:]]
    name_field = "NAMELSAD" if level == "county" and "NAMELSAD" in fields else "NAME"
    n = 0
    for sr in reader.iterShapeRecords():
        x0, y0, x1, y1 = sr.shape.bbox
        if x1 < bbox[1] or x0 > bbox[3] or y1 < bbox[0] or y0 > bbox[2]:
            continue
        geom = shape(sr.shape.__geo_interface__)
        if not geom.intersects(region):
            continue
        geom = geom.simplify(0.00005, preserve_topology=True)
        n += 1
        yield (level, sr.record[fields.index(name_field)], geom)
    print(f"  {n} {level} areas from {path}", file=sys.stderr)


# Census street names are abbreviated ("Emily Morgan Cir"); people say them in full.
SUFFIXES = {"aly": "Alley", "ave": "Avenue", "blvd": "Boulevard", "byp": "Bypass", "cir": "Circle", "ct": "Court",
            "cv": "Cove", "dr": "Drive", "expy": "Expressway", "fwy": "Freeway", "hwy": "Highway", "ln": "Lane",
            "loop": "Loop", "pass": "Pass", "path": "Path", "pkwy": "Parkway", "pl": "Place", "plz": "Plaza",
            "pt": "Point", "rd": "Road", "rdg": "Ridge", "row": "Row", "run": "Run", "sq": "Square", "st": "Street",
            "ter": "Terrace", "trce": "Trace", "trl": "Trail", "tpke": "Turnpike", "vw": "View", "walk": "Walk",
            "way": "Way", "xing": "Crossing", "crk": "Creek", "holw": "Hollow", "hts": "Heights", "spgs": "Springs",
            "grv": "Grove", "mdw": "Meadow", "mdws": "Meadows", "pk": "Park", "frk": "Fork", "cyn": "Canyon"}
DIRECTIONS = {"n": "North", "s": "South", "e": "East", "w": "West", "ne": "Northeast", "nw": "Northwest",
              "se": "Southeast", "sw": "Southwest"}


def full_street_name(name):
    words = name.split()
    if not words:
        return name
    out = list(words)
    if len(out) > 1 and out[0].lower() in DIRECTIONS:
        out[0] = DIRECTIONS[out[0].lower()]
    if len(out) > 1 and out[-1].lower() in DIRECTIONS:
        out[-1] = DIRECTIONS[out[-1].lower()]
        if len(out) > 2 and out[-2].lower() in SUFFIXES:
            out[-2] = SUFFIXES[out[-2].lower()]
    elif len(out) > 1 and out[-1].lower() in SUFFIXES:
        out[-1] = SUFFIXES[out[-1].lower()]
    return " ".join(out)


def num(v):
    try:
        return int(str(v).strip())
    except (TypeError, ValueError):
        return None


def load_ranges(db, paths, bbox):
    """House number ranges along each side of every street, from the Census Bureau (public domain).
    Treker estimates your house number from where you are along the street."""
    import shapefile  # pyshp
    rows, cells, rid = [], [], 0
    for path in paths:
        try:
            reader = shapefile.Reader(path)
        except Exception as e:
            print(f"  skipping {path}: {e}", file=sys.stderr)
            continue
        fields = [f[0] for f in reader.fields[1:]]
        ix = {k: fields.index(k) for k in ("FULLNAME", "LFROMHN", "LTOHN", "RFROMHN", "RTOHN", "ZIPL", "ZIPR") if k in fields}
        if "FULLNAME" not in ix:
            continue
        for sr in reader.iterShapeRecords():
            rec = sr.record
            name = rec[ix["FULLNAME"]]
            lf, lt = num(rec[ix["LFROMHN"]]), num(rec[ix["LTOHN"]])
            rf, rt = num(rec[ix["RFROMHN"]]), num(rec[ix["RTOHN"]])
            if not name or (lf is None and rf is None):
                continue
            pts = sr.shape.points
            if len(pts) < 2:
                continue
            rid += 1
            blob = bytearray()
            cs = set()
            for lon, lat in pts:
                blob += struct.pack("<ii", round(lat * 1e6), round(lon * 1e6))
                cs.add(cell_of(lat, lon))
            rows.append((rid, full_street_name(name), lf, lt, rf, rt,
                         rec[ix["ZIPL"]] if "ZIPL" in ix else "", rec[ix["ZIPR"]] if "ZIPR" in ix else "", bytes(blob)))
            cells.extend((c, rid) for c in cs)
            if len(rows) > 20000:
                db.executemany("INSERT INTO ranges VALUES (?,?,?,?,?,?,?,?,?)", rows)
                db.executemany("INSERT INTO range_cells VALUES (?,?)", cells)
                rows.clear(); cells.clear()
    db.executemany("INSERT INTO ranges VALUES (?,?,?,?,?,?,?,?,?)", rows)
    db.executemany("INSERT INTO range_cells VALUES (?,?)", cells)
    print(f"  {rid} house number ranges", file=sys.stderr)


def tidy_street(name):
    """Address files often shout ("EMILY MORGAN CIR"); say it the normal way, in full."""
    name = " ".join(name.split())
    words = name.split()
    shouting = sum(1 for w in words if w.isupper() and len(w) > 1)
    if name.islower() or shouting * 2 >= len(words):
        # Mixed files like "EMILY MORGAN Circle" count as shouting too. Road prefixes keep their capitals.
        name = " ".join(w if any(ch.isdigit() for ch in w) or w.upper() in KEEP_CAPS else w.capitalize() for w in words)
    return full_street_name(name)


KEEP_CAPS = {"FM", "RM", "CR", "SH", "US", "IH", "NE", "NW", "SE", "SW", "II", "III", "IV", "PR", "SR", "HWY"}


def load_points(db, path):
    """Real address points (each house's own location), so house numbers are exact, not estimated."""
    import csv
    rows, n = [], 0
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            num, street = (r.get("number") or "").strip(), (r.get("street") or "").strip()
            if not num or not street:
                continue
            try:
                lat, lon = float(r["lat"]), float(r["lon"])
            except (KeyError, ValueError):
                continue
            rows.append((num, tidy_street(street), "", (r.get("postcode") or "")[:5], lat, lon, cell_of(lat, lon)))
            n += 1
            if len(rows) > 50000:
                db.executemany("INSERT INTO addresses VALUES (?,?,?,?,?,?,?)", rows); rows.clear()
    db.executemany("INSERT INTO addresses VALUES (?,?,?,?,?,?,?)", rows)
    print(f"  {n} address points", file=sys.stderr)


def build_zip_cities(db):
    """The city each zip code's mail goes to, for places outside any city's limits (like Pecan Grove,
    whose mail says Richmond): the city most addresses in that zip code give."""
    rows = db.execute("""SELECT postcode, city, COUNT(*) n FROM addresses
                         WHERE postcode != '' AND city != '' GROUP BY postcode, city ORDER BY postcode, n DESC""").fetchall()
    best = {}
    for z, c, n in rows:
        best.setdefault(z[:5], c)
    db.executemany("INSERT OR REPLACE INTO zip_city VALUES (?,?)", best.items())
    print(f"  {len(best)} zip codes with a mailing city", file=sys.stderr)


def check(db):
    """Stops the build if the map is clearly broken, so a bad map never reaches anyone's phone."""
    q = lambda sql: db.execute(sql).fetchone()[0]
    problems = []
    if q("SELECT COUNT(*) FROM streets") < 100: problems.append("almost no streets")
    if q("SELECT COUNT(*) FROM junctions") < 50: problems.append("almost no intersections")
    if q("SELECT COUNT(*) FROM areas WHERE level = 'state'") < 1: problems.append("no state boundary")
    # Very small regions (like the U.S. Virgin Islands) may have no Census places.
    if q("SELECT COUNT(*) FROM areas WHERE level = 'city'") < 1 and q("SELECT COUNT(*) FROM streets") > 5000:
        problems.append("no city boundaries")
    if q("SELECT COUNT(*) FROM pois") < 50: problems.append("almost no places")
    return problems


SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE streets (id INTEGER PRIMARY KEY, name TEXT, kind TEXT, geom BLOB);
CREATE TABLE street_cells (cell INTEGER, street INTEGER);
CREATE TABLE junctions (id INTEGER PRIMARY KEY, lat REAL, lon REAL, names TEXT, cell INTEGER);
CREATE TABLE pois (id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL, cell INTEGER, address TEXT, osm TEXT);
CREATE TABLE addresses (number TEXT, street TEXT, city TEXT, postcode TEXT, lat REAL, lon REAL, cell INTEGER);
CREATE TABLE ranges (id INTEGER PRIMARY KEY, street TEXT, lfrom INTEGER, lto INTEGER, rfrom INTEGER, rto INTEGER,
    zipl TEXT, zipr TEXT, geom BLOB);
CREATE TABLE range_cells (cell INTEGER, range INTEGER);
CREATE TABLE zip_city (zip TEXT PRIMARY KEY, city TEXT);
CREATE TABLE areas (id INTEGER PRIMARY KEY, level TEXT, name TEXT,
    minlat REAL, minlon REAL, maxlat REAL, maxlon REAL, geom BLOB);
"""

INDEXES = """
CREATE INDEX street_cells_cell ON street_cells(cell);
CREATE INDEX junctions_cell ON junctions(cell);
CREATE INDEX pois_cell ON pois(cell);
CREATE INDEX pois_name ON pois(name COLLATE NOCASE);
CREATE INDEX addresses_cell ON addresses(cell);
CREATE INDEX addresses_street ON addresses(street COLLATE NOCASE);
CREATE INDEX streets_name ON streets(name COLLATE NOCASE);
CREATE INDEX areas_box ON areas(minlat, maxlat);
CREATE INDEX range_cells_cell ON range_cells(cell);
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pbf")
    ap.add_argument("out")
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--zcta", help="Census ZCTA shapefile for exact zip code areas")
    ap.add_argument("--census", nargs="*", default=[],
                    help="Census shapefiles for state, county and city areas (tl_*_state, tl_*_county, tl_*_place)")
    ap.add_argument("--country", help="Country name for the whole region, e.g. United States")
    ap.add_argument("--addrfeat", nargs="*", default=[], help="Census ADDRFEAT shapefiles: house number ranges along streets")
    ap.add_argument("--points", help="CSV of address points (number,street,unit,postcode,lat,lon), e.g. from Overture Maps")
    args = ap.parse_args()

    print("Pass 1: finding junctions", file=sys.stderr)
    p1 = Pass1()
    p1.apply_file(args.pbf)
    junctions = p1.junction_nodes()
    del p1
    print(f"  {len(junctions)} junction nodes", file=sys.stderr)

    db = sqlite3.connect(args.out)
    db.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA page_size=4096;")
    db.executescript(SCHEMA)

    print("Pass 2: streets, places, addresses, areas", file=sys.stderr)
    p2 = Pass2(db, junctions)
    p2.apply_file(args.pbf, locations=True, idx="flex_mem")
    p2.flush(force=True)

    rows = []
    for i, pos in enumerate(p2.junction_pos):
        if pos is None:
            continue
        names = sorted(p2.junction_names[i])
        rows.append((i, pos[0], pos[1], "|".join(names), cell_of(pos[0], pos[1])))
    db.executemany("INSERT INTO junctions VALUES (?,?,?,?,?)", rows)

    # Neighborhoods mapped only as points become small round areas (about 600 m across).
    for level, name, lat, lon in p2.place_nodes:
        from shapely.geometry import Point
        p2.areas.append((level, name, Point(lon, lat).buffer(0.004, quad_segs=4)))

    b = p2.bbox
    if args.zcta:
        p2.areas = [a for a in p2.areas if a[0] != "zip"] + list(zcta_areas(args.zcta, b))
    # State, county and city boundaries from the Census Bureau replace OpenStreetMap's: regional
    # OpenStreetMap extracts cut big boundaries at their edges, and the Census ones are official.
    for path in args.census:
        level = "state" if "_state" in path else "county" if "_county" in path else "city"
        found = list(census_areas(path, b, level))
        if found:
            p2.areas = [a for a in p2.areas if a[0] != level] + found
    if args.country:
        p2.areas = [a for a in p2.areas if a[0] != "country"]
        p2.areas.append(("country", args.country, box(b[1] - 0.5, b[0] - 0.5, b[3] + 0.5, b[2] + 0.5)))

    if args.addrfeat:
        load_ranges(db, args.addrfeat, b)
    if args.points:
        load_points(db, args.points)
    build_zip_cities(db)

    arows = []
    for i, (level, name, geom) in enumerate(p2.areas):
        minx, miny, maxx, maxy = geom.bounds
        arows.append((i + 1, level, name, miny, minx, maxy, maxx, pack_rings(geom)))
    db.executemany("INSERT INTO areas VALUES (?,?,?,?,?,?,?,?)", arows)

    b = p2.bbox
    meta = {
        "id": args.id, "name": args.name, "format": "1",
        "minlat": b[0], "minlon": b[1], "maxlat": b[2], "maxlon": b[3],
        "built": datetime.date.today().isoformat(),
    }
    db.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    db.executescript(INDEXES)
    db.commit()
    if not args.id.startswith("test"):
        problems = check(db)
        if problems:
            raise SystemExit("Map check failed: " + ", ".join(problems))
    db.execute("VACUUM")
    db.close()
    counts = {t: sqlite3.connect(args.out).execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
              for t in ("streets", "junctions", "pois", "addresses", "areas")}
    print("Done:", counts, file=sys.stderr)


if __name__ == "__main__":
    main()

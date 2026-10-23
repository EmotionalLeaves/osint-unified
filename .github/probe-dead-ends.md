# Sources that were probed and are not coming back

Kept so a later pass does not spend another CI round trip rediscovering
these. Each line is what the probe actually returned, not a guess.

## Refused on purpose, and the refusal is to be respected

- **Bureau of Meteorology, Australia** (`bom.gov.au`) — Darwin VAAC and
  Australian warnings. Returns 403 with a page that says in plain words
  that the site does not support web scraping and that automated access
  should stop. That is a clear statement of intent from the publisher.
  Do not probe it again, and do not try it with a different user agent:
  the answer is no, not a technical obstacle.
- **ReliefWeb / HDX** (`api.reliefweb.int`) — 406 "Blocked due to bot
  activity", with an address to write to if you think you are a human.
  Worth asking them properly if this layer ever matters enough; not
  worth working around.
- **Smithsonian Global Volcanism Program** (`volcano.si.edu`) — 403
  behind a request-verification interstitial.

## Needs an account or a key, which this app does not do

- `data.blitzortung.org` — 401. Global lightning, and it would be a
  good layer; the archive is members-only.
- `api.vtexplorer.com`, AISHub, aisstream.io — all AIS, all keyed.

## Live but not usable as a feed

- **Tokyo VAAC** (`data.jma.go.jp/vaac/`) and **Toulouse VAAC**
  (`vaac.meteo.fr`) — both answer, both are HTML index pages of
  advisories rather than data. Usable only by scraping a list page and
  then parsing free-text advisories into coordinates, which is a lot of
  fragile work for a layer GDACS partly covers already.
- `vaarweginformatie.nl`, `gis.vta.ee` — Dutch and Estonian waterway
  services. Answer, but neither exposes vessel positions.

## Answers, and is already used elsewhere or adds nothing

- `ngdc.noaa.gov/hazel/.../tsunamis/events` — the historical tsunami
  catalogue, not a live feed.
- `api.unhcr.org` — works, keyless, but it is yearly country statistics
  with no geometry.
- `environment.data.gov.uk/flood-monitoring` — works and is good, but
  it is England only.

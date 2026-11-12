# Energy API and collection

Optional project JSON-v1 `energy` configuration is validated independently by the server. Fields include `version:1`, `enabled`, contracts with ISO-date `from`, `electricityMode` (`single`, `bands`, `hourly`) and 1/3/24 prices in €/kWh, nullable gas €/Smc; `nominal`/`power`/`counter` electricity profiles; optional whole-home meter; `off`/`counter`/`thermal` gas with H in W/K, gross-calorific-value efficiency (PCS), PCS in kWh/Smc, m³→Smc coefficient, and zones. `EnergyConfig` defines fields/limits. Configuration/history contain no credentials.

Authenticated endpoints; writes require `X-SH3D-CSRF`:

| Method | Path under `/api/v1` | Content |
| --- | --- | --- |
| GET | `/projects/{id}/energy?year=2026` | `months` (12 months), `monthly` (actuals), `status` |
| POST | `/projects/{id}/energy/sample` | Requests shared sampling; returns collection status |
| PUT | `/projects/{id}/energy/monthly` | `{month:"2026-11", utility:"electricity"|"gas", amount:250, cost:65|null}` |
| DELETE | `/projects/{id}/energy/monthly/{YYYY-MM}/{utility}` | Removes override and reveals the automatic aggregate again |

`months`: `{month,electricity,gas,devices:[{id,name,total}]}`. Missing totals are `null`, not zero. `total`: `{amount,cost,knownCost,unpriced,observedHours,qualities}`. Quantities use kWh/Smc; `cost` is null when incomplete, `knownCost` retains the priced part, and `unpriced` is quantity without a price. `qualities`: `sensor`, `nominal`, `thermal`, `manual`. Electrical `sensor` data includes power integration, not only counters. Hours describe observed intervals, not device runtime; manual actuals have zero hours. Home totals use the configured whole-home meter or otherwise available included profiles together; do not add device figures to the total.

Tables added with `CREATE TABLE IF NOT EXISTS`: `energy_buckets` (UTC hour, source, quality, quantity, cost, unpriced quantity, observed seconds), `energy_monthly` (per-month/utility override). Project foreign keys cascade on deletion. Projects/contracts retain existing ETags/revisions; samples do not change home revisions. Full ZIP format is v2 with version-1 `energy.json`; restore also accepts v1 ZIPs and atomically applies energy/project/assets under a new project ID. Temporary baselines are excluded. Expanded limit: 150 MB; no silent truncation.

The collector shares one HA GET `/api/states` every 60 seconds across enabled homes. Manual/automatic attempts have a minimum 10-second gap. No I/O occurs without enabled homes and valid owner sessions with unlocked RAM keys. Collection does not keep sessions alive. Restarting requires sign-in; no new permanent key, secret store, or host service. Access tokens/snapshots are transient; only normalized aggregates persist. Closing a browser is not the same as session expiration.

Power uses trapezoidal integration in W/kW. Counters use Wh/kWh/MWh deltas, or gas Smc/m³/m3 × C. Resets and jumps above 1,000 kWh/h or 100 Smc/h are discarded. Gaps above 300 seconds, unknown units, and unavailable sources are excluded. Configuration changes/restarts discard the previous baseline. Costs are captured during sampling, not retroactively recalculated; slowly updated counters give approximate time allocation. Europe/Rome, Italian bands/national holidays including Easter Monday; UTC instants handle DST.

Thermal gas: `H × zoneWeight × max(0,Tin−Tout) / (1000 × efficiencyPCS × PCSkWhSmc)` in Smc/h, plus base Smc/day / 24. Zone weight follows radiator count. `hvac_action=heating` enables a zone; absent that attribute, use Tin≤setpoint+0.2 °C with global heating enabled. Metered gas/thermal estimates are alternatives. This is an indicative steady-state model excluding inertia/solar gains/water supply temperature; manual values are explicit, with no automatic fallback on sensor faults.

Primary sources: [ARERA time bands](https://www.arera.it/en/bolletta/glossario-dei-termini/dettaglio/fasce-orarie), [ARERA gross calorific value (PCS)](https://www.arera.it/en/bolletta/glossario-dei-termini/dettaglio/potere-calorifico-superiore-convenzionale-p), [BRE SAP 10.2, heat loss/ventilation, worksheet 37–39](https://files.bregroup.com/SAP/SAP%2010.2%20-%2020-08-2021.pdf), [HA REST](https://contributors.home-assistant.io/core/internals/rest-api/). This does not implement the full SAP method or estimate a complete utility bill.

# Ensayo SII en Paper

Prueba optativa del `EconomyWatchdog` del jar candidato, con Paper real,
proveedor Vault ficticio persistente y un `Player` sintetico. No habilita el
entrypoint completo de Odysseia, no enlaza sBank ni usa jugadores o datos reales.
No equivale a activar el candidato en staging o produccion.

Desde la raiz del repo, con staging detenido y su recurso bloqueado:

```bash
~/ai-hub/scripts/build_seguro.sh 25 "$PWD" python3 scripts/ensayar_sii_paper.py \
  --paper /ruta/paper-26.2.jar \
  --candidate "$PWD/target/Odysseia-1.1.3.jar" \
  --vault /ruta/VaultAPI.jar \
  --libraries /ruta/staging/libraries \
  --cache /ruta/staging/cache \
  --report /ruta/privada/informe.json
```

Usar una unidad transitoria `systemd-run --user` con `Slice=saori-agentes.slice`,
`CPUQuota=200%` y `MemoryMax=3G` para contener todo el ensayo. Requiere JDK25,
Paper y sus dependencias ya disponibles. El directorio del informe debe existir.

El runner compila solo el fixture, conserva las clases del candidato sin
modificarlas, cambia el descriptor del plugin y agrega VaultAPI. Crea un
servidor temporal en loopback con puerto efimero, whitelist y sin RCON/query.
Las copias de libraries/cache no modifican el staging original. Al terminar,
elimina el servidor ficticio; conserva solo hashes y resultados en el informe.

Son cinco procesos JVM distintos: flujo normal con respaldo/restauracion y
fallo de escritura; caida despues del debito; recuperacion sin replay; caida
despues del abono; recuperacion sin replay. Las caidas usan `Runtime.halt(77)`
despues de forzar a disco el saldo ficticio, antes de confirmar el ledger SII.
Cada proceso debe alcanzar `Done` y emitir su marcador; un timeout falla y
termina exclusivamente su proceso de prueba. No simula un corte electrico.

La restauracion copia juntos ledger y saldo ficticio con el vigilante detenido.
Nunca usar este procedimiento para restaurar solo el YAML de una economia real.
Un marcador pendiente exige conciliacion con el proveedor; no permite bajar a
la version anterior del lector ni borrar intenciones para repetir movimientos.

#!/usr/bin/env python3
"""Genera app/src/main/assets/oui.tsv.gz a partir de los registros públicos de la IEEE.

Uso:
    python3 tools/generar_oui.py            # descarga los CSV de standards-oui.ieee.org
    python3 tools/generar_oui.py DIR        # usa oui.csv, mam.csv, oui36.csv y cid.csv ya descargados en DIR

Formato de salida (una línea por asignación):  TIPO<TAB>PREFIJO_HEX<TAB>ORGANIZACION
  TIPO: L = MA-L (24 bits), M = MA-M (28 bits), S = MA-S (36 bits), C = CID (24 bits, MAC local)
"""
import csv
import gzip
import io
import os
import sys
import urllib.request

FUENTES = {
    "L": ("oui.csv", "https://standards-oui.ieee.org/oui/oui.csv"),
    "M": ("mam.csv", "https://standards-oui.ieee.org/oui28/mam.csv"),
    "S": ("oui36.csv", "https://standards-oui.ieee.org/oui36/oui36.csv"),
    "C": ("cid.csv", "https://standards-oui.ieee.org/cid/cid.csv"),
}

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SALIDA = os.path.join(RAIZ, "app", "src", "main", "assets", "oui.tsv.gz")


def leer(tipo, directorio):
    nombre, url = FUENTES[tipo]
    if directorio:
        with open(os.path.join(directorio, nombre), encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as r:
        return r.read().decode("utf-8")


def main():
    directorio = sys.argv[1] if len(sys.argv) > 1 else None
    filas = []
    for tipo in FUENTES:
        for fila in csv.DictReader(io.StringIO(leer(tipo, directorio))):
            prefijo = fila["Assignment"].strip().upper()
            org = " ".join(fila["Organization Name"].split())
            if prefijo and org:
                filas.append(f"{tipo}\t{prefijo}\t{org}")
    filas.sort()
    os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
    with gzip.open(SALIDA, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write("\n".join(filas) + "\n")
    print(f"{len(filas)} asignaciones -> {SALIDA}")


if __name__ == "__main__":
    main()

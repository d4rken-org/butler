---
title: Einrichtung und Berechtigungen
description: "Butlers Speicherberechtigungen, optionaler App-Zugriff sowie Porter, Shizuku und Root erklärt."
permalink: /de/setup
lang: de
translation_key: setup
source_hash: 38cee95741fe8f09
---
# Einrichtung und Berechtigungen
{: #setup--permissions }

Öffne **Einstellungen** und dann **Berechtigungen einrichten**, um Butlers Zugriffsrechte zu prüfen. Du musst nicht alles aktivieren. Butler fragt nach Zugriff, wenn eine Aufgabe ihn benötigt. Die verfügbaren Möglichkeiten hängen von deiner Android-Version und deinem Gerät ab.

Für die normale Dateiverwaltung genügt zunächst der Speicherzugriff. Root- und ADB-Zugriff sind optional, keine Voraussetzung.

## Speicherberechtigungen
{: #storage-permissions }

Mit Speicherzugriff kann Butler den gemeinsam genutzten Speicher verwalten, einschließlich üblicher Ordner wie Downloads und Documents. Nutze die Karte **Externen Speicher verwalten** in der Berechtigungseinrichtung:

- Ab Android 11 öffnet die Karte Androids Einstellung **Zugriff auf alle Dateien**. Erlaube den Zugriff und kehre zu Butler zurück.
- Unter Android 8 bis 10 zeigt Android stattdessen einen Berechtigungsdialog. Erlaube dort den Zugriff auf Dateien.

„Alle Dateien“ bedeutet nicht jede Datei auf dem Gerät. Android schützt weiterhin private App-Daten und einige Systembereiche. Einschränkungen für `Android/data` und `Android/obb` hängen von der Android-Version, der System-Dateiauswahl und der Gerätesoftware ab. Eine Berechtigung, die auf einem älteren Gerät funktioniert hat, muss auf einem neueren nicht denselben Ordner zugänglich machen.

Ist ein Ordner nicht zugänglich, nutze die Zugriffsmöglichkeiten, die Butler für diesen Speicherort anbietet. Auch erweiterte Rechte garantieren nicht, dass jeder Pfad auf jedem Gerät erreichbar ist.

### Storage Access Framework
{: #saf }

Das Storage Access Framework (SAF) ist Androids System, um einen Ordner oder Speicheranbieter auszuwählen und einer App Zugriff darauf zu geben. Es hilft bei Speicherorten, die nicht über gewöhnlichen Dateizugriff erreichbar sind, etwa Speicher, den eine andere App bereitstellt.

Wenn Butler die System-Ordnerauswahl öffnet:

1. Navigiere zum gewünschten Ordner oder Anbieter.
2. Wähle **Diesen Ordner verwenden** und bestätige den Zugriff, sofern Android es erlaubt.
3. Kehre zu Butler zurück und versuche den Zugriff erneut.

Eine deaktivierte Schaltfläche **Diesen Ordner verwenden** ist eine Systembeschränkung, kein Berechtigungsschalter in Butler. Geschützte Speicherorte werden möglicherweise gar nicht angeboten. Deaktiviere oder entferne nicht Androids System-App für Dateien/Dokumente, um dies zu umgehen. Butler benötigt sie für die Auswahl.

Fehlt ein Anbieter oder funktioniert ein zuvor freigegebener Speicherort nicht mehr, prüfe, ob die Anbieter-App installiert und aktiviert ist und du dort angemeldet bist. Möglicherweise musst du den Speicherort erneut freigeben.

## Nutzungsstatistiken
{: #usage-statistics }

Mit dem Nutzungszugriff kann Butler den Speicherbedarf von Apps anzeigen und abschätzen, welche Apps kürzlich aktiv waren. Zum Durchsuchen gewöhnlicher Dateien ist er nicht nötig.

Öffne die Einstellungen für den Nutzungszugriff über Butlers Einrichtungskarte, wähle Butler und aktiviere den Zugriff, wenn du diese Funktionen nutzen möchtest. Gerätehersteller können diesen Bildschirm anders benennen oder anordnen.

## Benachrichtigungen
{: #notifications }

Die Benachrichtigungsberechtigung erlaubt Butler, Benachrichtigungen anzuzeigen. Erteile sie, wenn du Benachrichtigungen zu Vorgängen außerhalb des aktuellen Bildschirms sehen möchtest. Hintergrundbeschränkungen unterscheiden sich je nach Gerät. Die Benachrichtigungsberechtigung allein hebt Akkubeschränkungen nicht auf.

## App-Inventar
{: #app-inventory }

Butler verwendet Androids Liste installierter Apps für das Werkzeug Apps und für Informationen zu Dateien, die zu Apps gehören. Die zugehörige Berechtigung heißt `QUERY_ALL_PACKAGES`.

Dieser Zugriff ist normalerweise automatisch verfügbar. Manche Gerätesoftware hat eine zusätzliche Berechtigung für die App-Liste oder kann den Zugriff entziehen. Wenn Butler meldet, dass die Liste nicht verfügbar ist, folge den Anweisungen auf der Einrichtungskarte und prüfe die Berechtigungseinstellungen des Geräts für Butler.

Wie App-Informationen verwendet werden, steht in der [Datenschutzerklärung](/privacy#query-installed-apps).

## ADB-Zugriff mit Porter oder Shizuku
{: #adb-access }

[Porter](https://porter.darken.eu/) und [Shizuku](https://shizuku.rikka.app/) erlauben kompatiblen Apps, unterstützte Aktionen mit erweiterten Rechten auszuführen. Werden sie über Debugging gestartet, bieten sie Zugriff mit den Rechten der Android-Shell, keinen uneingeschränkten Root-Zugriff.

Butler unterstützt beide und bevorzugt Porter, wenn es verfügbar ist. Du musst nicht beide installieren.

1. Installiere und starte den gewählten Dienst. Folge der [Einrichtungsanleitung von Porter](https://porter.darken.eu/setup) oder [Shizuku](https://shizuku.rikka.app/guide/setup/).
2. Kehre in Butler zu **Berechtigungen einrichten** zurück und aktiviere den ADB-Zugriff.
3. Erlaube dem Dienst den Zugriff für Butler, wenn er danach fragt.
4. Prüfe, ob Butlers Einrichtungskarte **Verbunden** anzeigt.

Nach einem Neustart muss der Dienst möglicherweise erneut gestartet werden. Wartet Butler auf eine Verbindung, prüfe zuerst die Dienst-App und dann, ob Butler dort autorisiert ist.

Anleitungen zu drahtlosem Debugging, Kopplung und herstellerspezifischen Startschritten findest du beim jeweiligen Dienst. Sie können sich unabhängig von Butler ändern.

## Root-Zugriff
{: #root-access }

Root gibt Butler weitreichenden Zugriff auf das Gerät. Aktiviere ihn nur, wenn dein Gerät bereits gerootet ist und du die Folgen verstehst. Butler rootet das Gerät nicht für dich.

Nutze die Root-Einrichtungskarte und erlaube Butler den Zugriff in deinem Root-Manager. Wird der Zugriff verweigert, prüfe die Berechtigungsliste des Root-Managers.

Root-Zugriff kann Systemdateien und private App-Daten zugänglich machen. Das Löschen oder Ändern dieser Dateien kann Apps oder das Betriebssystem beschädigen. Lege Backups an und ändere Dateien nicht allein deshalb, weil Butler sie erreichen kann.

## Weiterhin kein Zugriff?
{: #still-blocked }

Notiere den genauen Ordner, den Vorgang und die Fehlermeldung. Sieh unter [Häufige Fragen und Fehlerbehebung](/de/faq) nach oder [zeichne ein Debug-Protokoll auf](/de/bugs#debug-log), wenn du das Problem reproduzieren kannst.

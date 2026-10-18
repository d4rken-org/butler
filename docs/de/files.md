---
title: Mit Dateien arbeiten
description: "Dateien in Butler kopieren, verschieben, löschen und wiederherstellen und die Grenzen des Speicherzugriffs verstehen."
permalink: /de/files
lang: de
translation_key: files
source_hash: d3d3ed20321505c4
---
# Mit Dateien arbeiten
{: #working-with-files }

Mit einem **Explorer**-Tab kannst du Ordner durchsuchen und Dateien verwalten. Welche Aktionen angeboten werden, hängt von den ausgewählten Elementen und den Möglichkeiten des Speicherorts ab.

## Dateien auswählen
{: #select-files }

Halte eine Datei oder einen Ordner gedrückt, um eine Auswahl zu beginnen. Tippe dann auf weitere Elemente, um sie hinzuzufügen. Die Aktionsleiste zeigt Aktionen für die Auswahl. Über die Dateioptionen erreichst du weitere Aktionen für einzelne Elemente.

Prüfe die Auswahl vor einer destruktiven Aktion, besonders wenn du mit mehreren Tabs arbeitest.

## Kopieren und verschieben
{: #copy-and-move }

1. Wähle die Dateien oder Ordner am Quellort aus.
2. Wähle **Kopieren**, um die Originale zu behalten, oder **Ausschneiden**, um sie zu verschieben.
3. Navigiere zum Ziel, im selben Explorer oder in einem anderen Tab.
4. Füge die Elemente ein und prüfe mögliche Konflikt- oder Berechtigungsabfragen.

Zwei Explorer-Tabs in getrennten Ansichtsbereichen erleichtern es, Quelle und Ziel gleichzeitig zu sehen. Siehe [Tabs und Ansichtsbereiche](/de/getting-started#tabs-and-panes).

Übertragungen benötigen gegebenenfalls Zugriff auf beide Speicherorte. Netzwerkspeicher und Speicher, den andere Apps bereitstellen, können andere Möglichkeiten bieten als lokaler Speicher. Schlägt eine Übertragung fehl, prüfe ihr Ergebnis vor einem erneuten Versuch. Einige Dateien wurden möglicherweise bereits verarbeitet.

## Einen Vorgang verfolgen
{: #follow-an-operation }

Die Vorgangsleiste zeigt laufende Arbeiten an. Öffne einen Vorgang für Details oder nutze **Verlauf**, um das Ergebnis später zu prüfen. Lies gemeldete Probleme, statt davon auszugehen, dass alle ausgewählten Dateien verarbeitet wurden.

Das Abbrechen eines Vorgangs verhindert weitere Arbeit, macht aber bereits abgeschlossene Schritte nicht rückgängig. Halte den Quellspeicher und gegebenenfalls die Netzwerkverbindung verfügbar, bis der Vorgang beendet ist.

## Löschen und wiederherstellen
{: #trash }

Butler hat einen eigenen Papierkorb, der standardmäßig aktiviert ist. Du findest seine Einstellungen unter **Einstellungen**, **Explorer-Einstellungen**. Es gibt Grenzen für die Aufbewahrungsdauer und den Speicherplatz, den der Papierkorb belegen darf.

Wenn der Papierkorb aktiviert ist, werden gelöschte Dateien im eigenen Speicher des Geräts dorthin verschoben, statt endgültig entfernt zu werden. Ordner, die über die System-Dateiauswahl geöffnet wurden, und Netzwerkspeicherorte haben keinen Papierkorb; dort ist das Löschen endgültig. Kann Butler den Papierkorb für ein Element nicht verwenden, lies die Abfrage, bevor du einer endgültigen Löschung zustimmst.

Zum Wiederherstellen öffnest du **Papierkorb** auf der Explorer-Startseite, wählst das Element und dann **Wiederherstellen**. Butler stellt es am ursprünglichen Speicherort wieder her. Dieser muss weiterhin verfügbar und beschreibbar sein. Löse mögliche Namenskonflikte, die die App anzeigt.

> Der Papierkorb ist kein Backup. Gelöschte Dateien liegen in Butlers eigenem App-Ordner. Abgelaufene Aufbewahrungsfristen, das Leeren des Papierkorbs, das Löschen von Butlers Speicher oder die Deinstallation von Butler entfernen sie.

Dateien im Papierkorb belegen weiterhin Gerätespeicher, bis du sie dort endgültig löschst. Endgültig gelöschte Dateien können mit Butler nicht wiederhergestellt werden.

## Suchen und bearbeiten
{: #search-and-edit }

Erstelle einen **Suche**-Tab, um Dateien zu finden. Wähle die zu durchsuchenden Speicherorte und grenze die Ergebnisse mit Filtern ein. Die Suche nach Dateinamen und die Suche in Dateiinhalten sind unterschiedliche Aufgaben. Prüfe deshalb die Suchoptionen. Für genauere Suchmuster stehen reguläre Ausdrücke zur Verfügung.

Öffne Text im **Editor**, wenn du ihn ändern möchtest. Speichere deine Änderungen vor dem Verlassen und lege vor dem Bearbeiten wichtiger Dateien eine separate Kopie an. Die Berechtigung zum Lesen einer Datei bedeutet nicht automatisch, dass du sie auch speichern darfst.

## Netzwerkspeicher
{: #network-storage }

Butler unterstützt SMB-Freigaben, SFTP-Server und WebDAV-Server. Füge im Explorer einen Netzwerkspeicherort hinzu und gib die Serverdaten ein, die du von dessen Administration erhalten hast. Hinzufügen und Testen ist kostenlos; das Durchsuchen benötigt Pro.

- **SMB** wird häufig für Windows-Freigaben und NAS-Geräte verwendet.
- **SFTP** verwendet SSH. Prüfe den Fingerabdruck des Hostschlüssels mit der Serveradministration, bevor du einem neuen oder geänderten Schlüssel vertraust.
- **WebDAV** wird von Nextcloud, ownCloud und vielen NAS-Geräten verwendet. Ist das Zertifikat des Servers selbst signiert oder vertraut das Gerät ihm nicht, zeigt Butler seinen Fingerabdruck und fragt, bevor es ihm vertraut. Ändert sich ein vertrautes Zertifikat später, bricht Butler ab und fragt erneut. Akzeptiere es nur, wenn du weißt, warum es sich geändert hat.

Dein Gerät muss den Server erreichen können, etwa über dasselbe Netzwerk oder ein geeignetes VPN. Butler macht einen privaten Server nicht über das Internet erreichbar. Gib einen Server nicht öffentlich frei, nur um ein Verbindungsproblem zu beheben.

Halte Passwörter und private Schlüssel aus Screenshots, öffentlichen Fehlermeldungen und geteilten Protokollen heraus. Schlägt der Zugriff fehl, prüfe Adresse, Zugangsdaten, Serverberechtigungen und Netzwerkverbindung.

## Wenn sich ein Ordner nicht öffnen lässt
{: #when-a-folder-will-not-open }

Beginne mit [Einrichtung und Berechtigungen](/de/setup). Der Zugriff auf alle Dateien, eine Freigabe über die System-Ordnerauswahl und erweiterte Rechte sind unterschiedliche Mechanismen. Eine Freigabe macht nicht automatisch jeden Speicherort zugänglich.

Besteht das Problem weiter, notiere Ordnerpfad, Vorgang und genaue Fehlermeldung für einen [Fehlerbericht](/de/bugs). Entferne persönliche Namen oder andere private Pfadangaben, bevor du sie öffentlich teilst.

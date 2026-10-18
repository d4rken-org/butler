---
title: Häufige Fragen und Fehlerbehebung
description: "Antworten zu Butlers Berechtigungen, Upgrades, nicht zugänglichen Ordnern und Dateivorgängen."
permalink: /de/faq
lang: de
translation_key: faq
source_hash: db529cef8dfa9230
---
# Häufige Fragen und Fehlerbehebung
{: #faq--troubleshooting }

## Brauche ich Root?
{: #do-i-need-root }

Nein. Butler kann gewöhnliche Dateien im gemeinsam genutzten Speicher ohne Root verwalten. Android schützt einige Speicherorte und App-Aktionen. Diese benötigen möglicherweise zusätzliche Rechte oder bleiben nicht verfügbar. Siehe [Einrichtung und Berechtigungen](/de/setup).

## Warum ist Android/data weiterhin nicht zugänglich?
{: #why-is-androiddata-still-inaccessible }

Androids Berechtigung zum Zugriff auf alle Dateien gibt keinen uneingeschränkten Zugriff auf die Daten anderer Apps. Die verfügbaren Möglichkeiten hängen von deiner Android-Version und der Gerätesoftware ab. Nutze die Optionen, die Butler für den Speicherort anbietet, und lies [Speicherberechtigungen](/de/setup#storage-permissions).

Wenn die System-Ordnerauswahl einen Ordner verweigert, hilft es nicht, dieselbe Berechtigung immer wieder zu erteilen.

## Warum wartet Butler auf Porter oder Shizuku?
{: #why-is-butler-waiting-for-porter-or-shizuku }

Prüfe in der Porter- oder Shizuku-App, ob der Dienst läuft und Butler autorisiert ist. Ein über Debugging gestarteter Dienst muss nach einem Neustart möglicherweise erneut gestartet werden. Kehre anschließend in Butler zu **Berechtigungen einrichten** zurück. Siehe [ADB-Zugriff](/de/setup#adb-access).

## Warum kann ich eine Datei lesen, aber nicht ändern?
{: #why-can-i-read-a-file-but-not-change-it }

Lese- und Schreibrechte sind getrennt. Der Speicherort kann schreibgeschützt sein, der Server kann dein Konto einschränken oder ein Speicheranbieter unterstützt den Vorgang nicht. Prüfe die genaue Fehlermeldung und die Berechtigungen sowohl für die Quelle als auch für das Ziel.

## Warum hat das Löschen von Dateien keinen Speicherplatz freigegeben?
{: #why-did-deleting-files-not-free-space }

Dateien im Papierkorb belegen weiterhin Speicher. Öffne **Papierkorb** im Explorer und lösche nur die Elemente endgültig, die du nicht mehr benötigst. Lies vor dem Leeren [Löschen und wiederherstellen](/de/files#trash).

## Kann ich eine gelöschte Datei wiederherstellen?
{: #can-i-recover-a-deleted-file }

Nur, wenn sie noch in Butlers Papierkorb vorhanden ist. Endgültig gelöschte Elemente, Elemente mit abgelaufener Aufbewahrungsfrist und Dateien, die ohne Papierkorb gelöscht wurden, lassen sich mit Butler nicht wiederherstellen. Der Papierkorb ersetzt kein Backup.

## Ein Kopier- oder Verschiebevorgang wurde unterbrochen. Soll ich ihn wiederholen?
{: #a-copy-or-move-stopped-should-i-repeat-it }

Prüfe zuerst die Details des Vorgangs oder **Verlauf**. Ein Vorgang kann einige Elemente verarbeiten, bevor ein Problem auftritt. Prüfe vor einem erneuten Versuch beide Speicherorte und lies Abfragen zum Überschreiben sorgfältig. Ein Abbruch macht bereits abgeschlossene Schritte nicht rückgängig.

## Warum kann ich einen Netzwerkspeicherort hinzufügen, aber nicht durchsuchen?
{: #why-can-i-add-a-network-location-but-not-browse-it }

Das Hinzufügen und Testen von Netzwerkspeicherorten ist kostenlos; das Durchsuchen benötigt Butler Pro. Ein erfolgreicher Verbindungstest garantiert außerdem nicht, dass dein Serverkonto jeden Ordner lesen oder beschreiben darf. Siehe [Netzwerkspeicher](/de/files#network-storage).

## Versionen und Upgrades
{: #versions-and-upgrades }

### Wo sollte ich Butler herunterladen?
{: #where-should-i-download-butler }

Nutze [Google Play](https://play.google.com/store/apps/details?id=eu.darken.butler) oder die [GitHub-Releases](https://github.com/d4rken-org/butler/releases). Das [offene Testprogramm](https://play.google.com/apps/testing/eu.darken.butler) bietet Play-Betaversionen. Butler ist derzeit in der Betaphase; Versionen auf GitHub können als Vorabversionen gekennzeichnet sein.

Signaturzertifikate findest du unter [Downloads überprüfen](/security). Deinstalliere deine vorhandene App nicht einfach wegen einer abweichenden Signatur. Bedenke vorher, welche App-Daten und Einstellungen dabei verloren gehen würden.

### Was ist Butler Pro?
{: #what-is-butler-pro }

Butler hat keine Werbung. Pro-Upgrades und Sponsoring helfen, die Entwicklung zu finanzieren. Einige erweiterte Funktionen benötigen Pro. Der Upgrade-Bildschirm der App zeigt das aktuelle Angebot. Die verfügbaren Funktionen können sich während der Entwicklung ändern.

Die Google-Play-Version nutzt Google Play für Käufe. Wenn du Pro bereits gekauft hast, prüfe, ob du das Konto des Kaufs verwendest, und nutze die Wiederherstellungsoption der App.

### Wie kann ich ohne Google Play upgraden?
{: #how-can-i-upgrade-without-google-play }

Die FOSS-Version auf GitHub bietet statt Google Play Billing einen Upgrade-Ablauf über Sponsoring. Öffne dort den Upgrade-Bildschirm und folge den Sponsoring-Anweisungen. Ein Play-Kauf und der Sponsoring-Ablauf der FOSS-Version sind unterschiedliche Mechanismen.

Du kannst die [Entwicklung auch direkt unterstützen](https://github.com/sponsors/d4rken).

## Sendet Butler meine Dateien oder Debug-Protokolle automatisch?
{: #does-butler-send-my-files-or-debug-logs-automatically }

Butler hat kein Tracking und versendet keine Absturzberichte automatisch. Debug-Protokolle und Absturzberichte bleiben auf dem Gerät, solange du sie nicht selbst teilst. Optionale Mitteilungen kontaktieren GitHub, ebenso die Update-Prüfung der GitHub-Version. Details stehen in der [Datenschutzerklärung](/privacy).

Wenn du einen Netzwerkspeicherort verwendest oder eine Datei ausdrücklich teilst, werden Daten an den gewählten Server oder die gewählte App gesendet. Debug-Protokolle können Dateipfade und Angaben zu installierten Apps enthalten. Teile sie daher nur mit Personen, denen du vertraust.

## Wo kann ich eine Frage stellen?
{: #where-can-i-ask-a-question }

Nutze [GitHub Discussions](https://github.com/d4rken-org/butler/discussions) oder [Discord](https://discord.gg/ktMbDBAp4K). Bei einem reproduzierbaren Problem hilft [Probleme melden](/de/bugs).

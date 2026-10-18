---
title: Probleme melden
description: "Einen Butler-Fehler melden und ein Debug-Protokoll aufzeichnen, ohne private Informationen öffentlich zu teilen."
permalink: /de/bugs
lang: de
translation_key: bugs
source_hash: 530e820b361c7c5e
---
# Probleme melden
{: #reporting-problems }

Prüfe, ob eine neuere Version das Problem behebt, und suche in [vorhandenen Issues](https://github.com/d4rken-org/butler/issues), bevor du ein neues eröffnest. Bei Berechtigungs- oder Verbindungsproblemen beginne mit [Einrichtung](/de/setup) und [Fehlerbehebung](/de/faq).

## Einen Fehlerbericht erstellen
{: #file-a-report }

[Öffne ein GitHub-Issue](https://github.com/d4rken-org/butler/issues/new/choose) mit folgenden Angaben:

- Was du erwartet hast und was tatsächlich passiert ist.
- Die nötigen Schritte, um das Problem zu reproduzieren.
- Butlers Version und ob du sie von Google Play oder GitHub bezogen hast.
- Dein Gerätemodell und deine Android-Version.
- Die Art des betroffenen Speichers: Gerätespeicher, SD-Karte, eine Speicheranbieter-App oder ein Netzwerkserver.
- Ob Root, Porter oder Shizuku verwendet wurde, gegebenenfalls einschließlich Version.
- Die genaue Fehlermeldung und ein Screenshot, falls er das Problem erklärt.

GitHub-Issues sind öffentlich. Entferne private Dateinamen, Kontodaten, Serveradressen und andere sensible Informationen aus allem, was du veröffentlichst.

## Ein Debug-Protokoll aufzeichnen
{: #debug-log }

Ein Debug-Protokoll zeichnet Butlers Aktionen auf, während du ein Problem reproduzierst.

1. Öffne **Einstellungen** und dann **Support**.
2. Öffne unter **Sonstiges** den Eintrag **Fehlerberichte**.
3. Tippe auf **Aufzeichnen**. Ein Aufnahmebanner bleibt sichtbar, während du Butler verwendest.
4. Reproduziere das Problem.
5. Tippe im Bereich Fehlerberichte auf **Beenden** oder nutze das Aufnahmebanner.
6. Öffne die fertige Aufnahme und wähle **Teilen**, um sie über Androids Teilen-Dialog zu exportieren.

Sehr kurze Aufnahmen verlangen vor dem Stoppen eine Bestätigung. Zeichne möglichst die tatsächlich fehlschlagende Aktion auf, nicht nur den Fehlerbildschirm.

Die Aufnahme kann nach einem Neustart von Butler fortgesetzt werden. Bei Problemen mit dem Start oder der Erkennung von Speicherorten kann es helfen, die Aufnahme vor dem Neustart zu beginnen. Denke daran, sie anschließend zu stoppen.

> Protokolle können Dateipfade, Angaben zu installierten Apps und andere sensible Informationen enthalten. Teile sie nur mit vertrauenswürdigen Personen. Hänge kein ungeprüftes Protokoll an ein öffentliches Issue an.

Um ein Protokoll privat zu senden, öffne **Einstellungen**, **Support** und dann **Entwickler kontaktieren**. Das Formular sendet eine E-Mail an [support@darken.eu](mailto:support@darken.eu) und kann ein Protokoll anhängen oder ein neues aufnehmen. Füge den Link zu deinem Issue hinzu, damit Bericht und Protokoll zugeordnet werden können. Verwende **Teilen**, um eine Aufnahme zu exportieren, statt sie mit einem Dateimanager zu suchen.

## Absturzberichte
{: #crash-reports }

Butler speichert Absturzberichte lokal. Öffne **Fehlerberichte**, um einen anzusehen und zu teilen. Berichte werden nicht automatisch versendet. Es gelten dieselben Vorsichtsmaßnahmen zum Datenschutz wie bei Debug-Protokollen.

Mehr zum Inhalt der Berichte steht in der [Datenschutzerklärung](/privacy#debug-log).

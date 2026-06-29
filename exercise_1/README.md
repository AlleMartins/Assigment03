# Smart Home Alarm System - Apache Pekko

Implementazione del sistema di allarme per smart home basato su Apache Pekko (actor model, Java 17).

## Architettura

Il sistema è costruito utilizzando l'approccio actor model di Apache Pekko Typed:

```
Main (entry point)
    |
    v
ActorSystem<Void> ("SmartHomeAlarmSystem")
    |
    +-- Guardian actor (spawna tutti i child actors)
        |
        +-- AlarmSystem (AbstractBehavior, state machine con TimerScheduler)
        |   |
        |   +-- States: DISARMED, EXIT_DELAY, ARMED, ENTRY_DELAY, ALARM
        |   +-- Handles: ArmSystem, ArmSystemPartial, DisarmSystem, SensorEvent, StopAlarm
        |
        +-- Sensor actors (protocollo proprio: Trigger, Reset)
        |   +-- Ogni sensore ha tipo (MOTION/DOOR/WINDOW) e zona
        |   +-- Invia SensorEvent all'AlarmSystem quando triggerato
        |
        +-- EventLogger (riceve e stampa AlarmEvent)
```

## State Machine

```
DISARMED ──[ArmSystem + PIN]──────────→ EXIT_DELAY
DISARMED ──[ArmSystemPartial + PIN]───→ EXIT_DELAY
EXIT_DELAY ──[timer scaduto]──────────→ ARMED
EXIT_DELAY ──[DisarmSystem + PIN]─────→ DISARMED
ARMED ──[SensorEvent in zona attiva]──→ ENTRY_DELAY
ARMED ──[DisarmSystem + PIN]──────────→ DISARMED
ENTRY_DELAY ──[timer scaduto]─────────→ ALARM
ENTRY_DELAY ──[DisarmSystem + PIN]────→ DISARMED
ALARM ──[StopAlarm + PIN]────────────→ DISARMED
```

## Stati del sistema

| Stato | Descrizione |
|-------|-------------|
| **DISARMED** | Il sistema è disattivato, i sensori sono inattivi |
| **EXIT_DELAY** | Tempo concesso all'utente per uscire dopo aver attivato l'armamento |
| **ARMED** | Il sistema è attivo, i sensori nelle zone attive sono monitorati |
| **ENTRY_DELAY** | Tempo concesso all'utente per disattivare dopo un rilevamento |
| **ALARM** | L'allarme è scatenato, solo il PIN corretto lo ferma |

## Componenti principali

### Model (`it.unibo.smarthome.model`)
- `State.java` - Enum degli stati del sistema
- `Zone.java` - Enum delle zone della casa (LIVING_AREA, SLEEPING_AREA, PERIMETER, GROUND_FLOOR, UPPER_FLOOR)
- `SensorType.java` - Tipi di sensori (MOTION, DOOR, WINDOW)
- `AlarmConfig.java` - Configurazione (delays, PIN)
- `AlarmCommand.java` - Comandi ricevibili dall'AlarmSystem (ArmSystem, ArmSystemPartial, DisarmSystem, SensorEvent, StopAlarm)
- `AlarmEvent.java` - Eventi emessi dal sistema (SystemStateUpdated, AlarmTriggered, AlarmStopped, ecc.)

### Alarm (`it.unibo.smarthome.alarm`)
- `AlarmSystem.java` - Implementazione della state machine con `AbstractBehavior` e `TimerScheduler`

### Sensor (`it.unibo.smarthome.sensor`)
- `Sensor.java` - Actor che simula un sensore con protocollo proprio (`Sensor.Command`)

### Main (`it.unibo.smarthome`)
- `Main.java` - Punto di ingresso con simulazione demo (guardian actor pattern)

### Test (`it.unibo.smarthome.alarm`)
- `AlarmSystemTest.java` - 12 test con Pekko TestKit

## Costruzione ed esecuzione

### Requisiti
- Java 17+
- Gradle 7+

### Build
```bash
./gradlew build
```

### Esecuzione
```bash
./gradlew run
```

### Test
```bash
./gradlew test
```

## Utilizzo programmatico

```java
// Creare la configurazione
AlarmConfig config = new AlarmConfig(
    Duration.ofSeconds(30), // exit delay
    Duration.ofSeconds(15), // entry delay
    "1234"                  // PIN
);

// Creare un event sink (ad esempio un TestProbe o un actor di logging)
ActorRef<AlarmEvent> eventSink = ...;

// Creare l'AlarmSystem actor (full arming — tutte le zone)
Behavior<AlarmCommand> alarmBehavior = AlarmSystem.create(config, eventSink);

// Armare il sistema
alarmSystem.tell(new AlarmCommand.ArmSystem("1234"));

// Disarmare
alarmSystem.tell(new AlarmCommand.DisarmSystem("1234"));

// Triggerare un sensore
alarmSystem.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.PERIMETER, true));

// Fermare l'allarme
alarmSystem.tell(new AlarmCommand.StopAlarm("1234"));
```

## Zone-based Control (Bonus)

Il sistema supporta arming parziale per zone:

```java
// Arming parziale: solo perimetro e piano terra attivi
alarmSystem.tell(new AlarmCommand.ArmSystemPartial("1234",
    Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
```

Oppure, specificando le zone alla creazione:

```java
Set<Zone> activeZones = Set.of(Zone.PERIMETER, Zone.LIVING_AREA);
Behavior<AlarmCommand> alarmBehavior = AlarmSystem.createWithZones(config, activeZones, eventSink);
```

### Esempio: Night Mode

Attivare perimetro e piano terra, lasciare il piano superiore libero:

```java
alarmSystem.tell(new AlarmCommand.ArmSystemPartial("1234",
    Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));

// I sensori al piano superiore vengono ignorati
// Gli utenti possono muoversi liberamente di sopra
```

Solo i sensori nelle zone attive possono triggerare l'entry delay o l'allarme.

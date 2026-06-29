# Smart Home Alarm System - Apache Pekko

Implementazione del sistema di allarme per smart home basato su Apache Pekko.

## Architettura

Il sistema è costruito utilizzando l'approccio actor model di Apache Pekko:

```
Main (entry point)
    |
    v
ActorSystem ("SmartHomeAlarmSystem")
    |
    +-- AlarmSystem (Behavior, state machine)
    |   |
    |   +-- States: DISARMED, EXIT_DELAY, ARMED, ENTRY_DELAY, ALARM
    |   +-- Handles: ArmSystem, DisarmSystem, SensorEvent, StopAlarm
    |
    +-- Sensor actors (per sensor type/zone)
    |
    +-- Event broadcast (AlarmEvent)
```

## Stati del sistema

| Stato | Descrizione |
|-------|-------------|
| **DISARMED** | Il sistema è disattivato, i sensori sono inattivi |
| **EXIT_DELAY** | Tempo concesso all'utente per uscire dopo aver attivato l'armamento |
| **ARMED** | Il sistema è attivo, i sensori nei zone attive sono monitorati |
| **ENTRY_DELAY** | Tempo concesso all'utente per disattivare dopo un rilevamento |
| **ALARM** | L'allarme è scatenato, solo il PIN corretto lo ferma |

## Componenti principali

### Model (`it.unibo.smarthome.model`)
- `State.java` - Enum degli stati del sistema
- `Zone.java` - Enum delle zone della casa
- `SensorType.java` - Tipi di sensori (MOTION, DOOR, WINDOW)
- `AlarmConfig.java` - Configurazione (delays, PIN)
- `AlarmCommand.java` - Comandi ricevibili dagli actor
- `AlarmEvent.java` - Eventi emessi dal sistema

### Alarms (`it.unibo.smarthome.alarm`)
- `AlarmSystem.java` - Main entry point, factory per il sistema
- `AlarmBehavior.java` - Implementazione della state machine

### Sensor (`it.unibo.smarthome.sensor`)
- `Sensor.java` - Simulazione di un sensore

### Main (`it.unibo.smarthome`)
- `Main.java` - Punto di ingresso con esempio di simulazione

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

// Creare l'actor system
ActorSystem<AlarmCommand> system = ActorSystem.create(
    AlarmSystem.create(config),
    "MyAlarmSystem"
);

// Attivare il sistema
system.tell(new AlarmCommand.ArmSystem("1234"));

// Disattivare
system.tell(new AlarmCommand.DisarmSystem("1234"));

// Triggerare un sensore
system.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.PERIMETER, true));

// Fermare l'allarme
system.tell(new AlarmCommand.StopAlarm("1234"));
```

## Zone-based Control (Bonus)

Il sistema supporta arming parziale per zone:

```java
Set<Zone> activeZones = Set.of(Zone.PERIMETER, Zone.LIVING_AREA);
ActorSystem<AlarmEvent> system = ActorSystem.create(
    AlarmSystem.createWithZones(config, activeZones),
    "PartialArmingSystem"
);
```

Solo i sensori nelle zone attive possono triggerare l'entry delay o l'allarme.

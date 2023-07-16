
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class Main {
    public static void main(String[] args) {

        Connection.setUrl(args[0]);
        Devices.setSmartHub(new SmartHub(Long.parseLong(args[1], 16), "HUB01"));
        Devices.setTimer(new Timer());
		Connection.whoIsHere();
        Devices.getEnvSensors().forEach(Connection::getStatus);
        Devices.getSwitches().forEach(Connection::getStatus);

        while (true) {
            Connection.listen();
        }
    }
}

class Connection {
    private static HttpURLConnection connection;
    private static String url;
    private static final Base64Encoder base64Encoder = new Base64Encoder();

    private static final Base64Decoder base64Decoder = new Base64Decoder();

    private static final Handler handler = new Handler();

    private static final Response res = new Response();

    public static void setUrl(String url) {
        Connection.url = url;
    }

    private static void connect() {
        try {
            URL url = new URL(Connection.url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void whoIsHere() {
        try {
            connect();

            CBOnlyDevName cbOnlyDevName = new CBOnlyDevName(new TString(Devices.getSmartHub().getName()));
            Payload payload = new Payload(
                    new Varuint(Devices.getSmartHub().getSrc()),
                    new Varuint(16383L),
                    new Varuint(Devices.getSmartHub().getSerial()),
                    DevType.SmartHub,
                    Cmd.WHOISHERE,
                    cbOnlyDevName
            );

            send(payload);
            List<Packet> packets = base64Decoder.decodePacketList(response());
            handler.handlePacketList(packets);
            for (int i = 0; i < 3; i++) {
                connect();
                process();
            }
        } catch (IOException e) {
            System.exit(99);
        }
    }

    public static void iAmHere() {
        try {
            connect();

            CBOnlyDevName cbOnlyDevName = new CBOnlyDevName(new TString(Devices.getSmartHub().getName()));
            Payload payload = new Payload(
                    new Varuint(Devices.getSmartHub().getSrc()),
                    new Varuint(16383L),
                    new Varuint(Devices.getSmartHub().getSerial()),
                    DevType.SmartHub,
                    Cmd.IAMHERE,
                    cbOnlyDevName
            );

            send(payload);
        } catch (IOException e) {
            System.exit(99);
        }
    }

    public static void setStatus(Device device, byte value) {
        if (!device.isOnTheNetwork) {
            return;
        }

        try {
            connect();

            CBValue cbValue = new CBValue(new TByte(value));
            Payload payload = new Payload(
                    new Varuint(Devices.getSmartHub().getSrc()),
                    new Varuint(device.address),
                    new Varuint(Devices.getSmartHub().getSerial()),
                    device.devType,
                    Cmd.SETSTATUS,
                    cbValue
            );

            send(payload);
            res.deviceResponseMap.put(device, 0);
            process();

        } catch (IOException e) {
            System.exit(99);
        }
    }

    public static void getStatus(Device device) {
        if (!device.isOnTheNetwork) {
            return;
        }

        try {
            connect();

            CBEmpty cbEmpty = new CBEmpty();
            Payload payload = new Payload(
                    new Varuint(Devices.getSmartHub().getSrc()),
                    new Varuint(device.address),
                    new Varuint(Devices.getSmartHub().getSerial()),
                    device.devType,
                    Cmd.GETSTATUS,
                    cbEmpty
            );

            send(payload);
            res.deviceResponseMap.put(device, 0);
            process();
        } catch (IOException e) {
            System.exit(99);
        }
    }

    public static void send(Payload payload) throws IOException {
        DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
        byte[] b = payload.encode();
        Packet packet = new Packet(new TByte((byte) b.length), payload, new TByte(CRC.calculate(b)));
        dos.writeBytes(base64Encoder.encode(packet));
        Devices.getSmartHub().setSerial(Devices.getSmartHub().getSerial() + 1);
    }

    public static void process() throws IOException {
        List<Packet> packets = base64Decoder.decodePacketList(response());
        checkResponses(packets);
        handler.handlePacketList(packets);

        if (connection.getResponseCode() == 204) {
            System.exit(0);
        } else if (connection.getResponseCode() != 200) {
            System.exit(99);
        }

        if (!res.deviceResponseMap.isEmpty()) {
            waitResponse();
        }
    }

    public static String response() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(Connection.connection.getInputStream()));
        StringBuilder stringBuilder = new StringBuilder();

        String line;

        while ((line = in.readLine()) != null) {
            stringBuilder.append(line);
        }

        return stringBuilder.toString();
    }

    public static void waitResponse() {
        try {
            while (!res.deviceResponseMap.isEmpty()) {
                connect();
                BufferedReader in = new BufferedReader(new InputStreamReader(Connection.connection.getInputStream()));
                StringBuilder stringBuilder = new StringBuilder();

                String line;

                while ((line = in.readLine()) != null) {
                    stringBuilder.append(line);
                }
                if (!stringBuilder.isEmpty()) {
                    List<Packet> packets = base64Decoder.decodePacketList(stringBuilder.toString());
                    checkResponses(packets);
                    handler.handlePacketList(packets);
                    stringBuilder.setLength(0);
                }
                System.out.println(connection.getResponseCode());

                if (connection.getResponseCode() == 204) {
                    System.exit(0);
                } else if (connection.getResponseCode() != 200) {
                    System.exit(99);
                }
            }
        } catch (IOException e) {
            System.exit(99);
        }
    }

    public static void listen() {
        connect();
        try {
            process();
        } catch (IOException e) {
            System.exit(99);
        }
    }

    private static void checkResponses(List<Packet> packets) {
        for (Device d : res.deviceResponseMap.keySet()) {
            if (packets.stream().anyMatch(p -> p.payload().src().val().equals(d.address))) {
                res.deviceResponseMap.remove(d);
            }
        }

        res.deviceResponseMap.replaceAll((d, v) -> v + 100);

        for (Device d : res.deviceResponseMap.keySet()) {
            if (res.deviceResponseMap.get(d) > 300) {
                d.isOnTheNetwork = false;
                res.deviceResponseMap.remove(d);
            }
        }
    }
}

class Handler {
    public void handlePacketList(List<Packet> packetList) {
        packetList.forEach(this::handlePacket);
    }

    private void handlePacket(Packet packet) {
        Cmd cmd = packet.payload().cmd();
        DevType devType = packet.payload().dev_type();

        switch (cmd) {
            case WHOISHERE -> {
                handleWhoIsHere(packet);
                Connection.iAmHere();
            }
            case IAMHERE -> {
                if (devType.equals(DevType.Lamp)) {
                    Devices.addLamp(new Lamp(packet.payload().src().val(), ((CBOnlyDevName)packet.payload().cmd_body()).dev_name().val()));
                } else if (devType.equals(DevType.Switch)) {
                    handleIAmHereWhereForSwitch(packet);
                } else if (devType.equals(DevType.EnvSensor)) {
                    handleIAmHereWhereForEnvSensor(packet);
                } else if (devType.equals(DevType.Socket)) {
                   Devices.addSocket(new Socket(packet.payload().src().val(), ((CBOnlyDevName)packet.payload().cmd_body()).dev_name().val()));
                }
            }
            case STATUS -> handleStatus(packet);
            case TICK -> Devices.getTimer().setTimestamp(((CBTick) packet.payload().cmd_body()).timestamp().val());
        }
    }

    private void handleIAmHereWhereForSwitch(Packet packet) {
        Switch Switch = new Switch(packet.payload().src().val(), ((CBSwitchWhere) packet.payload().cmd_body()).dev_name().val());
        Devices.addSwitch(Switch);

        for (TString name : ((CBSwitchWhere) packet.payload().cmd_body()).dev_names().dev_names().list()) {
            Switch.addName(name.val());
        }
    }

    private void handleIAmHereWhereForEnvSensor(Packet packet) {
        EnvSensor envSensor = new EnvSensor(packet.payload().src().val(), ((CBSensorWhere) packet.payload().cmd_body()).dev_name().val());
        envSensor.setSensors(((CBSensorWhere) packet.payload().cmd_body()).dev_props().sensors().val().byteValue());
        envSensor.setTriggers(((CBSensorWhere) packet.payload().cmd_body()).dev_props().triggers().list());

        Devices.addEnvSensor(envSensor);
    }

    private void handleStatus(Packet packet) {
        Device device = Devices.getDeviceByAddress(packet.payload().src().val());
        if (!device.isOnTheNetwork) {
            return;
        }

        switch (device.devType) {
            case Switch -> handleStatusWhereForSwitch(packet, (Switch) device);
            case Lamp -> ((Lamp) device).status = Status.of(((CBValue) packet.payload().cmd_body()).value().val());
            case Socket -> ((Socket) device).status = Status.of(((CBValue) packet.payload().cmd_body()).value().val());
            case EnvSensor -> handleStatusWhereForEnvSensor(packet, (EnvSensor) device);
        }
    }

    private void handleStatusWhereForSwitch(Packet packet, Switch Switch) {
        int value = ((CBValue) packet.payload().cmd_body()).value().val();
        Switch.setStatus(Status.of(value));
        Switch.getNameLst().forEach(n -> ((Switchable) Devices.getDeviceByName(n)).status = Status.of(value));
        Switch.getNameLst().forEach(n -> Connection.setStatus(Devices.getDeviceByName(n), (byte) value));
    }

    private void handleStatusWhereForEnvSensor(Packet packet, EnvSensor envSensor) {
        List<Varuint> values = ((CBValues) packet.payload().cmd_body()).values().list();

        byte ind = 0;
        for (Sensor s : envSensor.getSensors()) {
            Trig trig = envSensor.getTriggers().stream()
                    .filter(t -> t.getOp().getSensor().equals(s))
                    .findFirst().orElse(null);
            if (trig != null) {
                if ((trig.getOp().getCompare() == 1 && values.get(ind).val() > trig.getValue()) ||
                        (trig.getOp().getCompare() == 0 && values.get(ind).val() < trig.getValue())) {
                    Device dev = Devices.getDeviceByName(trig.getName());
                    switch (dev.devType) {
                        case Lamp, Socket -> {
                            ((Switchable) dev).status = Status.of(trig.getOp().getTurn());
                            Connection.setStatus(dev, trig.getOp().getTurn());
                        }
                        case Switch -> {
                            ((Switch) dev).setStatus(Status.of(trig.getOp().getTurn()));
                            ((Switch) dev).getNameLst()
                                    .forEach(n -> Connection.setStatus(Devices.getDeviceByName(n), trig.getOp().getTurn()));
                        }
                    }
                }
            }
            ind++;
        }
    }

    private void handleWhoIsHere(Packet packet) {
        switch (packet.payload().dev_type()) {
            case Switch -> {
                Switch Switch = (Switch) Devices.getDeviceByAddress(packet.payload().src().val());
                Switch.clearNameList();

                for (TString name : ((CBSwitchWhere) packet.payload().cmd_body()).dev_names().dev_names().list()) {
                    Switch.addName(name.val());
                }

                Switch.isOnTheNetwork = true;
            }
            case Lamp, Socket -> Devices.getDeviceByAddress(packet.payload().src().val()).isOnTheNetwork = true;
            case EnvSensor -> {
                EnvSensor envSensor = (EnvSensor) Devices.getDeviceByAddress(packet.payload().src().val());
                envSensor.setSensors(((CBSensorWhere) packet.payload().cmd_body()).dev_props().sensors().val().byteValue());
                envSensor.setTriggers(((CBSensorWhere) packet.payload().cmd_body()).dev_props().triggers().list());
                envSensor.isOnTheNetwork = true;
            }
        }
    }
}

final class Base64Encoder {
    private static final Base64.Encoder encoder = Base64.getUrlEncoder();

    public <T extends Encodable> String encode(T object) {
        final byte[] bytes = encoder.encode(object.encode());
        return new String(bytes, StandardCharsets.UTF_8).replace("=", "");
    }
}

final class Base64Decoder {
    private static final Base64.Decoder decoder = Base64.getUrlDecoder();

    public List<Packet> decodePacketList(String base64) {
        ArrayList<byte[]> bytesList = new ArrayList<>();
        ArrayList<Packet> packets = new ArrayList<>();
        final byte[] bytes = decoder.decode(base64.getBytes(StandardCharsets.UTF_8));
        int index = 0;

        while (index != bytes.length) {
            bytesList.add(Arrays.copyOfRange(bytes, index, index + bytes[index] + 2));
            index += bytes[index] + 2;
        }

        for (byte[] b : bytesList) {
            packets.add(decodePacket(b));
        }

        return packets;
    }

    public Packet decodePacket(byte[] bytes) {
        //final byte[] bytes = decoder.decode(base64.getBytes(StandardCharsets.UTF_8));
        final TByte length = new TByte(bytes[0]);
        final Payload payload = decodePayload(Arrays.copyOfRange(bytes, 1, length.val() + 1));
        final TByte crc8 = new TByte(bytes[length.val() + 1]);
        return new Packet(length, payload, crc8);
    }

    private Payload decodePayload(byte[] bytes) {
        int readIndex = 0;

        final Varuint src = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
        readIndex += src.countBytes();

        final Varuint dst = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
        readIndex += dst.countBytes();

        final Varuint serial = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
        readIndex += serial.countBytes();

        final DevType dev_type = DevType.of(new TByte(bytes[readIndex++]).val());

        final Cmd cmd = Cmd.of(new TByte(bytes[readIndex++]).val());

        final CmdBody cmd_body = decodeCmdBody(Arrays.copyOfRange(bytes, readIndex, bytes.length), dev_type, cmd);

        return new Payload(src, dst, serial, dev_type, cmd, cmd_body);
    }

    private CmdBody decodeCmdBody(byte[] bytes, DevType dev_type, Cmd cmd) {
        switch (cmd) {
            case WHOISHERE, IAMHERE -> {
                if (dev_type == DevType.EnvSensor) {
                    return decodeCmdBodyWhereForSensor(bytes);
                } else if (dev_type == DevType.Switch) {
                    return decodeCmdBodyWhereForSwitch(bytes);
                } else {
                    return new CBOnlyDevName(new TString(bytes));
                }
            }
            case STATUS -> {
                if (dev_type == DevType.EnvSensor) {
                    return new CBValues(new TArray<>(bytes, byteArray -> {
                        final Varuint varuint = new Varuint(byteArray);
                        return new Pair<>(varuint, varuint.countBytes());
                    }));
                } else if (dev_type == DevType.Switch || dev_type == DevType.Lamp || dev_type == DevType.Socket) {
                    return new CBValue(new TByte(bytes[0]));
                } else {
                    return CmdBody.EMPTY;
                }
            }
            case SETSTATUS -> {
                return new CBValue(new TByte(bytes[0]));
            }
            case TICK -> {
                return new CBTick(new Varuint(bytes));
            }
            default -> {
                return CmdBody.EMPTY;
            }
        }
    }

    private CBSensorWhere decodeCmdBodyWhereForSensor(byte[] bytes) {
        int readIndex = 0;

        final TString dev_name = new TString(bytes);
        readIndex += dev_name.countBytes();

        final TByte sensors = new TByte(bytes[readIndex++]);

        final TArray<Trigger> triggers = new TArray<>(
                Arrays.copyOfRange(bytes, readIndex, bytes.length),
                byteArray -> {
                    int index = 0;

                    final TByte op = new TByte(byteArray[index++]);

                    final Varuint value = new Varuint(Arrays.copyOfRange(byteArray, index, byteArray.length));
                    index += value.countBytes();

                    final TString name = new TString(Arrays.copyOfRange(byteArray, index, byteArray.length));
                    index += name.countBytes();

                    return new Pair<>(new Trigger(op, value, name), index);
                });

        return new CBSensorWhere(dev_name, new DP(sensors, triggers));
    }

    public CBSwitchWhere decodeCmdBodyWhereForSwitch(byte[] bytes) {
        int readIndex = 0;

        final TString dev_name = new TString(bytes);
        readIndex += dev_name.countBytes();

        final TArray<TString> names = new TArray<>(
                Arrays.copyOfRange(bytes, readIndex, bytes.length),
                byteArray -> {
                    int index = 0;

                    final TString name = new TString(Arrays.copyOfRange(byteArray, index, byteArray.length));
                    index += name.countBytes();

                    return new Pair<>(name, index);
                });

        return new CBSwitchWhere(dev_name, new DPNameList(names));
    }
}

interface Encodable {
    byte[] encode();
}

record Packet(TByte length, Payload payload, TByte crc8) implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(length.encode(), payload.encode(), crc8.encode());
    }
}

record Payload(Varuint src, Varuint dst, Varuint serial, DevType dev_type, Cmd cmd, CmdBody cmd_body)
        implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(src.encode(), dst.encode(), serial.encode(), dev_type.encode(), cmd.encode(), cmd_body.encode());
    }
}

interface CmdBody extends Encodable {
    CmdBody EMPTY = new CBEmpty();
}

record CBEmpty() implements CmdBody {
    @Override
    public byte[] encode() {
        return new byte[0];
    }
}

record CBTick(Varuint timestamp) implements CmdBody {
    @Override
    public byte[] encode() {
        return timestamp.encode();
    }
}

record CBOnlyDevName(TString dev_name) implements CmdBody {
    @Override
    public byte[] encode() {
        return dev_name.encode();
    }
}

record CBSensorWhere(TString dev_name, DP dev_props) implements CmdBody {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(dev_name.encode(), dev_props.encode());
    }
}

record CBSwitchWhere(TString dev_name, DPNameList dev_names) implements CmdBody {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(dev_name.encode(), dev_names.encode());
    }
}

record CBValue(TByte value) implements CmdBody {
    @Override
    public byte[] encode() {
        return value.encode();
    }
}

record CBValues(TArray<Varuint> values) implements CmdBody {
    @Override
    public byte[] encode() {
        return values.encode();
    }
}

record DP(TByte sensors, TArray<Trigger> triggers) implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(sensors.encode(), triggers.encode());
    }
}

record Trigger(TByte op, Varuint value, TString name) implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(op.encode(), value.encode(), name.encode());
    }
}

record DPNameList(TArray<TString> dev_names) implements Encodable {
    @Override
    public byte[] encode() {
        return dev_names.encode();
    }
}


interface Type<T> extends Encodable {
    T val();
}

interface BigType<T> extends Type<T> {
    int countBytes();
}

final class TByte implements Type<Integer> {
    private final Integer value;

    TByte(byte b) {
        this.value = Byte.toUnsignedInt(b);
    }

    @Override
    public Integer val() {
        return value;
    }

    @Override
    public byte[] encode() {
        return new byte[]{value.byteValue()};
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

final class TString implements BigType<String> {
    private final String value;
    private final int countBytes;

    TString(String val) {
        this.value = val;
        this.countBytes = val.length() + 1;
    }

    TString(byte[] bytes) {
        final StringBuilder builder = new StringBuilder();
        int count = Byte.toUnsignedInt(bytes[0]);
        for (int i = 1; i <= count; i++) {
            builder.appendCodePoint(bytes[i]);
        }

        this.value = builder.toString();
        this.countBytes = count + 1;
    }

    @Override
    public String val() {
        return value;
    }

    @Override
    public byte[] encode() {
        final byte[] bytes = new byte[countBytes];
        bytes[0] = Integer.valueOf(countBytes - 1).byteValue();
        byte[] valBytes = value.getBytes();
        System.arraycopy(valBytes, 0, bytes, 1, valBytes.length);
        return bytes;
    }

    @Override
    public int countBytes() {
        return countBytes;
    }

    @Override
    public String toString() {
        return value;
    }
}

final class Varuint implements BigType<Long> {
    private final Long value;
    private final int countBytes;

    Varuint(Long val) {
        this.value = val;
        countBytes = Math.abs(val) > 127? 2: 1;
    }

    Varuint(byte[] bytes) {
        long value = 0;
        int bitSize = 0;
        int read;

        int index = 0;
        do {
            read = bytes[index++];
            value += ((long) read & 0x7f) << bitSize;
            bitSize += 7;
            if (bitSize >= 64) {
                throw new ArithmeticException("ULEB128 value exceeds maximum value for long type.");
            }
        } while ((read & 0x80) != 0);

        this.value = value;
        this.countBytes = index;
    }

    @Override
    public Long val() {
        return value;
    }

    @Override
    public byte[] encode() {
        long val = value;
        List<Byte> bytes = new ArrayList<>();
        do {
            byte b = (byte) (val & 0x7f);
            val >>= 7;
            if (val != 0) {
                b |= 0x80;
            }
            bytes.add(b);
        } while (val != 0);

        byte[] ret = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            ret[i] = bytes.get(i);
        }
        return ret;
    }

    @Override
    public int countBytes() {
        return countBytes;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

final class TArray<T extends Encodable> implements BigType<T> {
    private final List<T> list;
    private final int countBytes;

    TArray(byte[] bytes, Function<byte[], Pair<T, Integer>> handler) {
        int readIndex = 0;
        int count = new TByte(bytes[readIndex++]).val();
        final List<T> arrayList = new ArrayList<>(count);
        for (; count > 0; count--) {
            Pair<T, Integer> pair = handler.apply(Arrays.copyOfRange(bytes, readIndex, bytes.length));
            readIndex += pair.right();

            arrayList.add(pair.left());
        }

        this.list = arrayList;
        this.countBytes = readIndex;
    }

    public List<T> list() {
        return list;
    }

    @Override
    public T val() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] encode() {
        final byte[] size = new byte[]{Integer.valueOf(list.size()).byteValue()};
        final List<byte[]> objects = list.stream().map(Encodable::encode).toList();

        final byte[] bytes = new byte[objects.stream().mapToInt(arr -> arr.length).sum() + 1];

        int index = Utils.concatBytes(bytes, size, 0);
        for (byte[] arr : objects) {
            index = Utils.concatBytes(bytes, arr, index);
        }
        return bytes;
    }

    @Override
    public int countBytes() {
        return countBytes;
    }

    @Override
    public String toString() {
        return list.toString();
    }
}

enum Cmd implements Encodable {
    WHOISHERE(0x01),
    IAMHERE(0x02),
    GETSTATUS(0x03),
    STATUS(0x04),
    SETSTATUS(0x05),
    TICK(0x06);

    Cmd(int b) {
    }

    public static Cmd of(int b) {
        return values()[b - 1];
    }

    @Override
    public byte[] encode() {
        return new byte[]{Integer.valueOf(ordinal() + 1).byteValue()};
    }
}

enum DevType implements Encodable {
    SmartHub(0x01),
    EnvSensor(0x02),
    Switch(0x03),
    Lamp(0x04),
    Socket(0x05),
    Clock(0x06);

    DevType(int b) {
    }

    public static DevType of(int b) {
        return values()[b - 1];
    }

    @Override
    public byte[] encode() {
        return new byte[]{Integer.valueOf(ordinal() + 1).byteValue()};
    }
}

enum Status {
    OFF(0), ON(1);

    Status(int i) {
    }

    public static Status of(int i) {
        return values()[i];
    }
}

enum Sensor {
    TEMPERATURE(1),
    HUMIDITY(2),
    ILLUMINATION(3),
    AIR_POLLUTION(4);

    Sensor(int i) {
    }

    public static Sensor of(byte i) {
        return values()[i - 1];
    }
}


record Pair<T, U>(T left, U right) {
}

final class Utils {
    public static int concatBytes(byte[] src, byte[] dst, int index) {
        for (int i = 0; i < dst.length; i++, index++) {
            src[index] = dst[i];
        }
        return index;
    }

    public static byte[] concatByteArrays(byte[]... arrays) {
        if (arrays.length == 0) {
            return new byte[0];
        }

        final int length = Arrays.stream(arrays).mapToInt(arr -> arr.length).sum();
        final byte[] bytes = new byte[length];

        int index = Utils.concatBytes(bytes, arrays[0], 0);
        for (int i = 1; i < arrays.length; i++) {
            index = Utils.concatBytes(bytes, arrays[i], index);
        }

        return bytes;
    }
}

class SmartHub {
    private final Long src;

    private final String name;
    private Long serial;

    public SmartHub(Long src, String name) {
        this.src = src;
        this.name = name;
        serial = 1L;
    }

    public Long getSrc() {
        return src;
    }

    public Long getSerial() {
        return serial;
    }

    public String getName() {
        return name;
    }

    public void setSerial(Long serial) {
        this.serial = serial;
    }
}

class Device {
    protected final Long address;
    protected final String name;
    public boolean isOnTheNetwork;
    protected final DevType devType;

    Device (Long address, String name, DevType devType, boolean f) {
        this.address = address;
        this.name = name;
        this.devType = devType;
        isOnTheNetwork = f;
    }
}

class Switchable extends Device {

    protected Status status;
    Switchable(Long address, String name, DevType devType, boolean f) {
        super(address, name, devType, f);
    }
}

class Lamp extends Switchable {

    Lamp(Long address, String name) {
        super(address, name, DevType.Lamp, true);
    }

    public String getName() {
        return name;
    }
}

class Socket extends Switchable{

    Socket(Long address, String name) {
        super(address, name, DevType.Socket, true);
    }

    public String getName() {
        return name;
    }
}

class EnvSensor extends Device {

    private final ArrayList<Sensor> sensors;
    private final ArrayList<Trig> triggers;

    EnvSensor(Long address, String name) {
        super(address, name, DevType.EnvSensor, true);
        this.sensors = new ArrayList<>();
        this.triggers = new ArrayList<>();
    }

    public ArrayList<Sensor> getSensors() {
        return sensors;
    }

    public ArrayList<Trig> getTriggers() {
        return triggers;
    }

    public String getName() {
        return name;
    }

    public void setSensors(byte s) {
        if (!sensors.isEmpty()) {
            sensors.clear();
        }
        byte op;

        for (byte b : new byte[]{1, 2, 3, 4}) {
            if ((s & b) == b) {
                sensors.add(Sensor.of(b));
            }
        }
    }

    public void setTriggers(List<Trigger> triggerList) {
        if (!triggers.isEmpty()) {
            triggers.clear();
        }
        byte op;

        for (Trigger trigger : triggerList) {
            OP OP = new OP();
            op = trigger.op().val().byteValue();
            if ((op & 0x1) == 0) {
                OP.setTurn((byte) 0);
            } else {
                OP.setTurn((byte) 1);
            }

            if ((op & 0x2) == 0) {
                OP.setCompare((byte) 0);
            } else {
                OP.setCompare((byte) 1);
            }

            OP.setSensor(Sensor.of((byte) ((op >> 2) + 1)));
            triggers.add(new Trig(OP, Math.toIntExact(trigger.value().val()), trigger.name().val()));
        }
    }

    @Override
    public String toString() {
        return "EnvSensor{" +
                "sensors=" + sensors +
                ", triggers=" + triggers +
                ", address=" + address +
                ", name='" + name + '\'' +
                ", isOnTheNetwork=" + isOnTheNetwork +
                ", devType=" + devType +
                '}';
    }
}

class Trig {
    private final OP op;
    private final int value;
    private final String name;

    public Trig(OP op, int value, String name) {
        this.op = op;
        this.value = value;
        this.name = name;
    }

    public OP getOp() {
        return op;
    }

    public int getValue() {
        return value;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Trig{" +
                "op=" + op +
                ", value=" + value +
                ", name='" + name + '\'' +
                '}';
    }
}

class OP {
    private byte turn;
    private byte compare;
    private Sensor sensor;

    OP () {}

    public OP(byte turn, byte compare, Sensor sensor) {
        this.turn = turn;
        this.compare = compare;
        this.sensor = sensor;
    }

    public byte getTurn() {
        return turn;
    }

    public byte getCompare() {
        return compare;
    }

    public Sensor getSensor() {
        return sensor;
    }

    public void setTurn(byte turn) {
        this.turn = turn;
    }

    public void setCompare(byte compare) {
        this.compare = compare;
    }

    public void setSensor(Sensor sensor) {
        this.sensor = sensor;
    }

    @Override
    public String toString() {
        return "OP{" +
                "turn=" + turn +
                ", compare=" + compare +
                ", sensor=" + sensor +
                '}';
    }
}

class Switch extends Device {
    private Status status;

    private final ArrayList<String> nameLst;

    Switch(Long address, String name) {
        super(address, name, DevType.Switch, true);
        status = Status.ON;
        nameLst = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public ArrayList<String> getNameLst() {
        return nameLst;
    }
    public void setStatus(Status status) {
        this.status = status;
    }

    public void addName(String name) {
        nameLst.add(name);
    }

    public void clearNameList() {
        nameLst.clear();
    }
}

class Timer {

    private Long timestamp;

    Timer() {}

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}

class Devices {
    private static SmartHub smartHub;
    private static Timer timer;
    private static final ArrayList<Lamp> lamps = new ArrayList<>();
    private static final ArrayList<Socket> sockets = new ArrayList<>();
    private static final ArrayList<Switch> switches = new ArrayList<>();
    private static final ArrayList<EnvSensor> envSensors = new ArrayList<>();
    private static final ArrayList<Device> devices = new ArrayList<>();

    public static SmartHub getSmartHub() {
        return smartHub;
    }

    public static Timer getTimer() {
        return timer;
    }

    public static ArrayList<Switch> getSwitches() {
        return switches;
    }

    public static ArrayList<EnvSensor> getEnvSensors() {
        return envSensors;
    }

    public static ArrayList<Device> getDevices() {
        return devices;
    }

    public static Device getDeviceByName(String name) {
        return devices.stream().filter(d -> d.name.equals(name)).findFirst().orElse(null);
    }

    public static Device getDeviceByAddress(Long address) {
        return Devices.getDevices().stream().filter(d -> Objects.equals(d.address, address)).findFirst().orElse(null);
    }

    public static void setSmartHub(SmartHub smartHub) {
        Devices.smartHub = smartHub;
    }

    public static void setTimer(Timer timer) {
        Devices.timer = timer;
    }

    public static void addLamp(Lamp lamp) {
        for (Lamp l : lamps) {
            if (l.getName().equals(lamp.getName())) {
                return;
            }
        }

        devices.add(lamp);
        lamps.add(lamp);
    }

    public static void addSocket(Socket socket) {
        for (Socket s : sockets) {
            if (s.getName().equals(socket.getName())) {
                return;
            }
        }

        devices.add(socket);
        sockets.add(socket);
    }

    public static void addSwitch(Switch Switch) {
        for (Switch s : switches) {
            if (s.getName().equals(Switch.getName())) {
                return;
            }
        }

        devices.add(Switch);
        switches.add(Switch);
    }

    public static void addEnvSensor(EnvSensor envSensor) {
        for (EnvSensor s : envSensors) {
            if (s.getName().equals(envSensor.getName())) {
                return;
            }
        }

        devices.add(envSensor);
        envSensors.add(envSensor);
    }
}

class Response {
    public final Map<Device, Integer> deviceResponseMap = new HashMap<>();

    Response () {}
}

class CRC {
    public static byte calculate(byte[] b) {
        final byte generator = 0x1D;
        byte crc = 0;

        for (byte currByte : b) {
            crc ^= currByte;

            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (byte)((crc << 1) ^ generator);
                }
                else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }
}
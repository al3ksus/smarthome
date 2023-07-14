
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
    private static final Base64Encoder base64Encoder = new Base64Encoder();
    private static final Base64Decoder base64Decoder = new Base64Decoder();
    private static final Connection connection = new Connection();

    public static void main(String[] args) {

//		Devices.setSmartHub(new SmartHub(1L, "HUB01"));
//		connection.whoIsHere("http://localhost:9998");
//		Listener listener = new Listener();
//		listener.run();
        log("DYEg_38BBgbo2a6plTHED4Eg_38CBgIHVElNRVIwMUEOgiD_fwEEAgZMQU1QMDIj");
    }

    private static void log(String str) {
        System.out.println("Input - " + str);
        final List<Packet> packets = base64Decoder.decodePackets(str);
        System.out.println("Packets - " + packets);
//		final String encoded = base64Encoder.encode(packet);
//		System.out.println("Encod - " + encoded);
    }
}

class Connection {
    private static HttpURLConnection connection;
    private static final Base64Encoder base64Encoder = new Base64Encoder();

    private static final Base64Decoder base64Decoder = new Base64Decoder();

    public void connect(String str) {
        try {
            URL url = new URL(str);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void whoIsHere(String url) {
        try {
            connect(url);
            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            CBOnlyDevName cbOnlyDevName = new CBOnlyDevName(new TString(Devices.getSmartHub().getName()));
            Payload payload = new Payload(
                    new Varuint(Devices.getSmartHub().getSrc()),
                    new Varuint(16383L),
                    new Varuint(Devices.getSmartHub().getSerial()),
                    DevType.SmartHub,
                    Cmd.WHOISHERE,
                    cbOnlyDevName);

            byte[] b = payload.encode();
            Packet packet = new Packet(new TByte((byte) b.length), payload, new TByte(CRC.calculate(b)));
            System.out.println(packet);
            dos.writeBytes(base64Encoder.encode(packet));
            Devices.getSmartHub().setSerial(Devices.getSmartHub().getSerial() + 1);

            BufferedReader in = new BufferedReader(new InputStreamReader(Connection.connection.getInputStream()));
            StringBuilder stringBuilder = new StringBuilder();

            String line;

            while ((line = in.readLine()) != null) {
                stringBuilder.append(line);
            }

            System.out.println(stringBuilder);
            response(url);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void response(String url) {
        try {
            for (int i = 0; i < 3; i++) {
                connect(url);
                BufferedReader in = new BufferedReader(new InputStreamReader(Connection.connection.getInputStream()));
                StringBuilder stringBuilder = new StringBuilder();

                String line;

                while ((line = in.readLine()) != null) {
                    stringBuilder.append(line);
                }
                if (!stringBuilder.isEmpty()) {
                    System.out.println(stringBuilder);
                    stringBuilder.setLength(0);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
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

    public List<Packet> decodePackets(String base64) {
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
    ON, OFF;
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

class Lamp {
    Status status;

    Long address;

    String name;

    Lamp(Long address, String name) {
        status = Status.ON;
        this.address = address;
        this.name = name;
    }

    public Status getStatus() {
        return status;
    }

    public Long getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setAddress(Long address) {
        this.address = address;
    }

    public void setName(String name) {
        this.name = name;
    }
}

class Switch {
    Status status;

    Long address;

    String name;

    Switch(Long address, String name) {
        status = Status.ON;
        this.address = address;
        this.name = name;
    }

    public Status getStatus() {
        return status;
    }

    public Long getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setAddress(Long address) {
        this.address = address;
    }

    public void setName(String name) {
        this.name = name;
    }
}

class Devices {
    private static SmartHub smartHub;
    private static final ArrayList<Lamp> lamps = new ArrayList<>();
    private static final ArrayList<Switch> switches = new ArrayList<>();

    public static SmartHub getSmartHub() {
        return smartHub;
    }

    public static ArrayList<Lamp> getLamps() {
        return lamps;
    }

    public static ArrayList<Switch> getSwitches() {
        return switches;
    }

    public static void setSmartHub(SmartHub smartHub) {
        Devices.smartHub = smartHub;
    }

    public static void addLamp(Lamp lamp) {
        lamps.add(lamp);
    }

    public static void addSwitch(Switch Switch) {
        switches.add(Switch);
    }
}



class CRC {
    public static byte calculate(byte[] b) {
        final byte generator = 0x1D;
        byte crc = 0; /* start with 0 so first byte can be 'xored' in */

        for (byte currByte : b) {
            crc ^= currByte; /* XOR-in the next input byte */

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
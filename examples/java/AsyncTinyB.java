import tinyb.*;
import java.util.*;
import java.time.*;

public class AsyncTinyB {
    private static final float SCALE_LSB = 0.03125f;
    static boolean running = true;

    static void printDevice(BluetoothDevice device) {
        System.out.print("Address = " + device.getAddress());
        System.out.print(" Name = " + device.getName());
        System.out.print(" Connected = " + device.getConnected());
        System.out.println();
    }

    static float convertCelsius(int raw) {
        return raw / 128f;
    }

    /*
     * This program connects to a TI SensorTag 2.0 and reads the temperature characteristic exposed by the device over
     * Bluetooth Low Energy. The parameter provided to the program should be the MAC address of the device.
     *
     * A wiki describing the sensor is found here: http://processors.wiki.ti.com/index.php/CC2650_SensorTag_User's_Guide
     *
     * The API used in this example is based on TinyB v0.3, which only supports polling, but v0.4 will introduce a
     * simplied API for discovering devices and services.
     */
    public static void main(String[] args) throws InterruptedException {

        if (args.length < 1) {
            System.err.println("Run with <device_address> argument");
            System.exit(-1);
        }

        /*
         * To start looking of the device, we first must initialize the TinyB library. The way of interacting with the
         * library is through the BluetoothManager. There can be only one BluetoothManager at one time, and the
         * reference to it is obtained through the getBluetoothManager method.
         */
        BluetoothManager manager = BluetoothManager.getBluetoothManager();

        /*
         * The manager will try to initialize a BluetoothAdapter if any adapter is present in the system. To initialize
         * discovery we can call startDiscovery, which will put the default adapter in discovery mode.
         */
        boolean discoveryStarted = manager.startDiscovery();

        System.out.println("The discovery started: " + (discoveryStarted ? "true" : "false"));

        /*
         * After discovery is started, new devices will be detected. We can find the device we are interested in
         * through the manager's find method.
         */
        BluetoothDevice sensor = manager.find(null, args[0], null, Duration.ofSeconds(10));

        /*
         * After we find the device we can stop looking for other devices.
         */
        manager.stopDiscovery();

        if (sensor == null) {
            System.err.println("No sensor found with the provided address.");
            System.exit(-1);
        }

        System.out.print("Found device: ");
        printDevice(sensor);

        if (sensor.connect())
            System.out.println("Sensor with the provided address connected");
        else {
            System.out.println("Could not connect device.");
            System.exit(-1);
        }

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                running = false;
                sensor.disconnect();
            }
        });

        /*
         * Our device should expose a temperature service, which has a UUID we can find out from the data sheet. The service
         * description of the SensorTag can be found here:
         * http://processors.wiki.ti.com/images/a/a8/BLE_SensorTag_GATT_Server.pdf. The service we are looking for has the
         * short UUID AA00 which we insert into the TI Base UUID: f000XXXX-0451-4000-b000-000000000000
         */
        BluetoothGattService tempService = sensor.find( "f000aa00-0451-4000-b000-000000000000");

        if (tempService == null) {
            System.err.println("This device does not have the temperature service we are looking for.");
            sensor.disconnect();
            System.exit(-1);
        }
        System.out.println("Found service " + tempService.getUuid());

        BluetoothGattCharacteristic tempValue = tempService.find("f000aa01-0451-4000-b000-000000000000");
        BluetoothGattCharacteristic tempConfig = tempService.find("f000aa02-0451-4000-b000-000000000000");
        BluetoothGattCharacteristic tempPeriod = tempService.find("f000aa03-0451-4000-b000-000000000000");

        if (tempValue == null || tempConfig == null || tempPeriod == null) {
            System.err.println("Could not find the correct characteristics.");
            sensor.disconnect();
            System.exit(-1);
        }

        System.out.println("Found the temperature characteristics");

        /*
         * Turn on the Temperature Service by writing 1 in the configuration characteristic, as mentioned in the PDF
         * mentioned above. We could also modify the update interval, by writing in the period characteristic, but the
         * default 1s is good enough for our purposes.
         */
        byte[] config = { 0x01 };
        tempConfig.writeValue(config);

        /*
         * Each second read the value characteristic and display it in a human readable format.
         */
        while (running) {
            byte[] tempRaw = tempValue.readValue();
            System.out.print("Temp raw = {");
            for (byte b : tempRaw) {
                System.out.print(String.format("%02x,", b));
            }
            System.out.print("}");

            /*
             * The temperature service returns the data in an encoded format which can be found in the wiki. Convert the
             * raw temperature format to celsius and print it. Conversion for object temperature depends on ambient
             * according to wiki, but assume result is good enough for our purposes without conversion.
             */
            int objectTempRaw = (tempRaw[0] & 0xff) | (tempRaw[1] << 8);
            int ambientTempRaw = (tempRaw[2] & 0xff) | (tempRaw[3] << 8);

            float objectTempCelsius = convertCelsius(objectTempRaw);
            float ambientTempCelsius = convertCelsius(ambientTempRaw);

            System.out.println(
                    String.format(" Temp: Object = %fC, Ambient = %fC", objectTempCelsius, ambientTempCelsius));

            Thread.sleep(1000);
        }
        sensor.disconnect();

    }
}

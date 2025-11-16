package com.example.mybluetoothchat;


import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;


/*
используется Bluetooth Classic (RFCOMM)
создается поток данных через сокет
Сервер:
    Создаёт сокет - socket()
    Привязывает его к IP и порту - bind()
    Готов слушать входящие подключения - listen()
    Принимает подключение от клиента - accept() создаёт новый сокет для общения
    Читает/пишет данные - recv() / send()
    Закрывает сокет - close()
Клиент:
    Создаёт сокет - socket()
    Подключается к серверу - connect()
    Отправляет/получает данные - send() / recv()
    Закрывает сокет - close()

 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BluetoothChat";
    private static final int REQUEST_ENABLE_BT = 1; // номер запроса на включение Bluetooth.
    private static final int REQUEST_LOCATION_PERMISSION = 2; // номер запроса разрешения на местоположение
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // стандартный SPP UUID
    //уникальный ключ по которому устройства коннектятся


    // UI элементы .xml
    private Button btnDiscover, btnSend, btnDisconnect;
    private EditText etMessage;
    private TextView tvChat;
    private ListView listDevices;

    // Bluetooth компоненты
    private BluetoothAdapter bluetoothAdapter; //  главный объект который управляет Bluetooth
    private ActivityResultLauncher<Intent> btEnableLauncher;
    private ArrayList <BluetoothDevice> deviceList = new ArrayList<>(); // массив c найденными устройствами

    private ActivityResultLauncher<String[]> bluetoothPermissionLauncher;
    private ArrayAdapter <String> deviceAdapter; // обеспечит связь deviceList со списком устройств на вижуале

    // Соединение
    private BluetoothSocket socket = null; // активное соединение с другим устройством
    private ConnectedThread connectedThread = null; //читающий и пишущий поток
    private AcceptThread acceptThread = null; // ожидающий входящие подключения поток (типа серверный)

    // Handler - обновление UI в фоновом потоке
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btEnableLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        startAcceptingConnections();
                    } else {
                        Toast.makeText(this, "Bluetooth должен быть включен", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
        );

        // Запрос Bluetooth-разрешений для Android 12+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean connectGranted = result.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false);
                        Boolean scanGranted = result.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false);
                        if (connectGranted && scanGranted) {
                            initBluetooth();
                        } else {
                            Toast.makeText(this, "Требуются разрешения Bluetooth для работы приложения", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
            );

            bluetoothPermissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            });
        } else {
            // Для Android 11 и ниже — старый путь
            initBluetooth();
        }

        initViews();
        setupClickListeners();
        setupDeviceListClickListener();
    }

//    @Override
//    protected void onCreate(Bundle savedInstanceState) { // параметр хранит состояние Activity при повороте экрана или завершения процесса
//        super.onCreate(savedInstanceState); //вызов из родительского класса
//        setContentView(R.layout.activity_main); // взять .xml и сделать его интерфейсом этого экрана
//
//        btEnableLauncher = registerForActivityResult(
//                new ActivityResultContracts.StartActivityForResult(),
//                result -> {
//                    if (result.getResultCode() == RESULT_OK) {
//                        // пользователь разрешил включить Bluetooth
//                        startAcceptingConnections();
//                    } else {
//                        // пользователь отказался
//                        Toast.makeText(this, "Bluetooth должен быть включен", Toast.LENGTH_SHORT).show();
//                        finish();
//                    }
//                }
//        );
//
//
//        initViews();
//        initBluetooth();
//        setupClickListeners();
//        setupDeviceListClickListener();
//    }


    private void initViews() { //найти все элементы интерфейса по их ID и сохранить ссылки в переменные
        // связь java-переменных с xml-элементами по android:id
        btnDiscover = findViewById(R.id.btnDiscover);
        btnSend = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        etMessage = findViewById(R.id.etMessage);
        tvChat = findViewById(R.id.tvChat);
        listDevices = findViewById(R.id.listDevices);

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1); //берёт список строк ["Устр 1", "Устр 2"] и отображает их в ListView
        listDevices.setAdapter(deviceAdapter); //привязка адаптера к списку
    }

	//Активация блютуза
    private void initBluetooth() {
        /*
        1. Проверка поддерживает ли устройство Bluetooth
        2. Включение Bluetooth
        3. запрос разрешения на местоположение
        4. Запуск AcceptThread, чтобы принимать входящие подключения.
        */
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter(); //метод который возвращает единственный адаптер Bluetooth на устройстве.
        if (bluetoothAdapter == null) { // 1
            Toast.makeText(this, "Bluetooth is unavailable", Toast.LENGTH_SHORT).show(); // покахывает всплывающее окно пользователю
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) { // 2
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            btEnableLauncher.launch(enableBtIntent); //запускает этот диалог и ждёт ответа
        } else {
            // 4
            startAcceptingConnections(); // сразу начинаем слушать подключения
        }

        // 3
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            //ContextCompat.checkSelfPermission - проверяет дано ли приложению разрешение на точное местоположение
            //если разрешение не дано, то показать системный диалог из запрашиваемых разрешений
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 2);
        }
    }


    @Override
	//явное получение разрешений во время работы
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); // Вызов родительского метода
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission needed to search for devices.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startAcceptingConnections() {
        //запускает сервер который будет ждать пока к тебе подключится другой человек
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
            appendToChat("Waiting for connections...");
        }
    }

    private void setupClickListeners() {
		//оживление кнопок
		// лямбда-выражение v -> ...
		// v = view 
        btnDiscover.setOnClickListener(v -> discoverDevices());
        btnSend.setOnClickListener(v -> sendMessage());
        btnDisconnect.setOnClickListener(v -> disconnect());
    }

    private void setupDeviceListClickListener() {//делаем список устройств "кликабельным"
		//назначение обработчика для списка устройств
        listDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BluetoothDevice device = deviceList.get(position); //выбранное устройство
                connectToDevice(device);
            }
        });
    }

    //Сканирование устройств
    private void discoverDevices() {
		// Т.е. поиск доступных устройств и показ их в списке,
		// чтобы пользователь выбрал одно и подключился

        // Проверяем разрешение
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }

        deviceList.clear();
        deviceAdapter.clear();

        //Сначала уже спаренные устройства
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                deviceList.add(device);
                deviceAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        }

        //поиск новых устройств
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery(); //метод асинхронный, результат в BroadcastReceiver

        // Регистрация BroadcastReceiver
		//фильтр, которого интересуют только уведомления с ACTION_FOUND
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(discoveryReceiver, filter); //получатель об уведомлениях о найденых устройствах
    }

	//слушатель системных событий
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { 
			//intent - объект содержащий данные о найденном устройстве

            String action = intent.getAction(); //возвращает строку-идентификатор события
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // Если нет разрешения — игнорируем
                    return;
                }

				//Parcelable - способ Android передавать объекты между компонентами
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !deviceList.contains(device)) {
                    deviceList.add(device);
                    deviceAdapter.add(device.getName() + "\n" + device.getAddress());
                }
            }
        }
    };

    //Поключение к выбранному устройству
    private void connectToDevice(BluetoothDevice device) {
        // Проверяем разрешение BLUETOOTH_ADMIN
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No permisson BLUETOOTH_ADMIN");
            Toast.makeText(this, "No permisson to cancel searching", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothAdapter.cancelDiscovery(); //стало безопасно

		//созданем выполняюшийся фоновый процесс
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = device.createRfcommSocketToServiceRecord(MY_UUID);
					//createRfcommSocketToServiceRecord - создает клиентский сокет для подключения
                    socket.connect();

                    runOnUiThread(new Runnable() { //обновление интерфейса после успешного подключения
                        @Override
                        public void run() {
                            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                appendToChat("Connected to: " + device.getName());
                            } else {
                                appendToChat("Connected to noname device");
                            }
                            btnSend.setEnabled(true);
                            btnDisconnect.setEnabled(true);
                        }
                    });

					// запуск потока обмена данными
                    connectedThread = new ConnectedThread(socket);
                    connectedThread.start();

                } catch (IOException e) {
                    Log.e(TAG, "Error: Not connected", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Not connected", Toast.LENGTH_SHORT).show();
                        }
                    });
                    try {
                        if (socket != null) socket.close();
                    } catch (IOException ignored) {}
                }
            }
        }).start();
    }

    private void sendMessage() {
        String message = etMessage.getText().toString().trim();
        if (!message.isEmpty() && connectedThread != null) {
            connectedThread.write(message);
            appendToChat("Me: " + message);
            etMessage.setText("");
        }
    }
    private void appendToChat(String message) {
        tvChat.append(message + "\n");
    }
    private void disconnect() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        btnSend.setEnabled(false);
        btnDisconnect.setEnabled(false);
        appendToChat("Disconnected!");
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        unregisterReceiver(discoveryReceiver);
    }

    // поток приема входящих подключений (сервер)
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() { //конструктор
            BluetoothServerSocket tmp = null;

            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No permission BLUETOOTH_CONNECT");
                serverSocket = null;
                return;
            }

            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord("BluetoothChat", MY_UUID);
				// создание серверного сокета
            } catch (IOException e) {
                Log.e(TAG, "Error: can't init server socket", e);
            }
            serverSocket = tmp;
        }


        public void run() {
            // если сокет не создан выходим
            if (serverSocket == null) {
                mainHandler.post(() -> {
                    appendToChat("Error: no permission BLUETOOTH_CONNECT");
                    Toast.makeText(MainActivity.this, "permission Bluetooth neeeded", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            BluetoothSocket socket;
            while (true) {
                try {
                    socket = serverSocket.accept(); //метод блокирующий
                } catch (IOException e) {
                    Log.e(TAG, "Error in accept()", e);
                    break;
                }

                if (socket != null) {
                    mainHandler.post(() -> {
                        appendToChat("Friend is connected");
                        btnSend.setEnabled(true);
                        btnDisconnect.setEnabled(true);
                    });
                    connectedThread = new ConnectedThread(socket);
                    connectedThread.start();
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Unsuccess to close server socket", e);
                    }
                    break;
                }
            }
        }
        public void cancel() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Unsuccess to close server socket", e);
            }
        }
    }

    private class ConnectedThread extends Thread { 
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream In = null;
            OutputStream Out = null;
            try {
                In = socket.getInputStream();
                Out = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error: can't init threads", e);
            }
            mmInStream = In;
            mmOutStream = Out;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {
                try {
                    bytes = mmInStream.read(buffer);
                    String incomingMessage = new String(buffer, 0, bytes);
                    mainHandler.post(() -> appendToChat("Friend: " + incomingMessage.trim()));
                } catch (IOException e) {
                    Log.e(TAG, "Error: can't read", e);
                    mainHandler.post(() -> appendToChat("Connection is closed"));
                    break;
                }
            }
        }

        public void write(String message) {
            try {
                mmOutStream.write((message + "\n").getBytes());
            } catch (IOException e) {
                Log.e(TAG, "Error: can't send ", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error: can't close socket", e);
            }
        }
    }
}
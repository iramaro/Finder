package ru.seva.finder;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.ContextMenu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SimpleCursorAdapter;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;


public class MainActivity extends AppCompatActivity {
    Button remember_btn;
    EditText text;
    ListView list, list_receive;
    TabHost tabHost;

    public static final String PHONES_COL = "phone";
    public static boolean activityRunning = false;  //у нас будет только один инстанс, поэтому вроде норм
    SharedPreferences sPref;

    Cursor cursor, cursor_answ;
    dBase baseConnect;
    SQLiteDatabase db;
    SimpleCursorAdapter adapter, adapter_answ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        remember_btn = (Button) findViewById(R.id.button);
        text = (EditText) findViewById(R.id.editText);
        list = (ListView) findViewById(R.id.lvSendTo);
        list_receive = (ListView) findViewById(R.id.lvReceiveFrom);
        activityRunning = true;

        sPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        //подрубаемся к базе
        baseConnect = new dBase(this);
        db = baseConnect.getWritableDatabase();

        String[] fromColons = {PHONES_COL};
        int[] toViews = {android.R.id.text1};
        cursor = db.query(dBase.PHONES_TABLE_OUT, null, null, null, null, null, null);

        //создаём адаптер списка запросов
        adapter = new SimpleCursorAdapter(this,
                android.R.layout.simple_list_item_1,
                cursor,
                fromColons,
                toViews,
                0);

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showSendMenu(view, id);
            }
        });


        cursor_answ = db.query(dBase.PHONES_TABLE_IN, null, null, null, null, null, null);
        int[] toViews2 = {android.R.id.text1};
        adapter_answ = new SimpleCursorAdapter(this,
                android.R.layout.simple_list_item_1,
                cursor_answ,
                fromColons,
                toViews2,
                0);  //колонка телефонов называется так же
        list_receive.setAdapter(adapter_answ);
        list_receive.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                click_on_trusted(view, id);
            }
        });
        //registerForContextMenu(list_receive);


        //неплохо было бы всё в XML переписать
        tabHost = findViewById(R.id.tabHost);
        tabHost.setup();
        TabHost.TabSpec tabspec = tabHost.newTabSpec("tag1");  //тэг нам ненужен, но он нужен конструктору
        tabspec.setContent(R.id.tab_out);
        tabspec.setIndicator(getString(R.string.request_numbs));
        tabHost.addTab(tabspec);

        tabspec = tabHost.newTabSpec("tag2");
        tabspec.setContent(R.id.tab_in);
        tabspec.setIndicator(getString(R.string.answer_numbs));
        tabHost.addTab(tabspec);

        //починка бага андроида с "фокусом" https://issuetracker.google.com/issues/36907655

        tabHost.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                tabHost.getViewTreeObserver().removeOnTouchModeChangeListener(tabHost);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {

            }
        });

        text.requestFocus();

        //ресивер остановки прогресс-бара
        LocalBroadcastManager.getInstance(this).registerReceiver(PGbar, new IntentFilter("disable_bar"));
    }

    //описание работы меню списка доверенных номеров
    private void click_on_trusted(final View v, final long id) {
        PopupMenu menu = new PopupMenu(this, v);
        menu.inflate(R.menu.context_menu_trusted);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Cursor query = db.query(dBase.PHONES_TABLE_IN, new String[] {dBase.PHONES_COL},
                        "_id = ?", new String[] {Long.toString(id)},
                        null, null, null);
                query.moveToFirst();
                String phone = query.getString(query.getColumnIndex(dBase.PHONES_COL));
                query.close();
                switch(item.getItemId()) {
                    case R.id.gps_send:
                        Intent gps_intent = new Intent(getApplicationContext(), GpsSearch.class);
                        gps_intent.putExtra("phone_number", phone);
                        getApplicationContext().startService(gps_intent);
                        Toast.makeText(v.getContext(), getString(R.string.coordinates_will_be_sent) + phone, Toast.LENGTH_LONG).show();
                        return true;
                    case R.id.wifi_send:
                        Intent wifi_intent = new Intent(getApplicationContext(), WifiSearch.class);
                        wifi_intent.putExtra("phone_number", phone);
                        getApplicationContext().startService(wifi_intent);
                        Toast.makeText(v.getContext(), getString(R.string.nets_will_be_sent) + phone, Toast.LENGTH_LONG).show();
                        return true;
                    case R.id.delete:
                        db.delete(dBase.PHONES_TABLE_IN, "_id = ?", new String[] {Long.toString(id)});
                        updateAnswList();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private BroadcastReceiver PGbar = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            TextView label = (TextView) findViewById(R.id.textProgress);
            ProgressBar bar = (ProgressBar) findViewById(R.id.progress);
            label.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
        }
    };

    static void write_to_hist(SQLiteDatabase base, String phone, Double lat, Double lon, @Nullable Integer accuracy,
                               String date, @Nullable String bat, @Nullable Double altitude,
                               @Nullable Float speed, @Nullable Float direction) {
        ContentValues cv = new ContentValues();
        cv.put("phone", phone);
        cv.put("lat", lat);
        cv.put("lon", lon);
        cv.put("date", date);

        if (altitude == null) {
            cv.putNull("height");
        } else {
            cv.put("height", altitude);
        }

        if (accuracy == null) {
            cv.putNull("acc");
        } else {
            cv.put("acc", accuracy);
        }

        if (bat == null) {
            cv.putNull("bat");
        } else {
            cv.put("bat", bat);
        }

        if (speed == null) {
            cv.putNull("speed");
        } else {
            cv.put("speed", speed);
        }

        if (direction == null) {
            cv.putNull("direction");
        } else {
            cv.put("direction", direction);
        }

        base.insert("history", null, cv);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        activityRunning = false;
        cursor.close();
        db.close();
    }


    private void showSendMenu(final View v, final long num_id) {
        PopupMenu menu = new PopupMenu(this, v);
        menu.inflate(R.menu.context_menu);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {

            private void sendSmsRequest(String key, String def_text, String phone) {
                SmsManager sManager = SmsManager.getDefault();
                if ((ContextCompat.checkSelfPermission(v.getContext(), Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) &&
                        (ContextCompat.checkSelfPermission(v.getContext(), Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)) {
                    sManager.sendTextMessage(phone, null, sPref.getString(key, def_text), null, null);
                    Toast.makeText(v.getContext(), R.string.request_has_been_send, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(v.getContext(), R.string.no_rights_for_sms, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Cursor query = db.query(dBase.PHONES_TABLE_OUT, new String[] {PHONES_COL},
                        "_id = ?", new String[] {Long.toString(num_id)},
                        null, null, null);
                query.moveToFirst();
                final String phone = query.getString(query.getColumnIndex(PHONES_COL));
                final TextView label = (TextView) findViewById(R.id.textProgress);
                final ProgressBar bar = (ProgressBar) findViewById(R.id.progress);
                switch (item.getItemId()) {
                    case R.id.wifi_id:
                        //предупреждение об интернете
                        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int witch) {
                                switch(witch) {
                                    case DialogInterface.BUTTON_POSITIVE:
                                        //ваще поифиг, юзверь оповещён что работать ничего не будет, инет не чекаем
                                        sendSmsRequest("wifi", "wifi_search", phone);
                                        label.setVisibility(View.VISIBLE);
                                        bar.setVisibility(View.VISIBLE);
                                        break;
                                    case DialogInterface.BUTTON_NEGATIVE:
                                        //ничего не отправляем
                                        break;
                                }
                            }
                        };

                        //проверим наличие сети
                        ConnectivityManager cManager = (ConnectivityManager) v.getContext().getSystemService(v.getContext().CONNECTIVITY_SERVICE);
                        NetworkInfo network_inf = cManager.getActiveNetworkInfo();

                        if (network_inf != null && network_inf.isConnected()) {
                            //сеть "активна", отправляем запрос не дёргая юзера
                            sendSmsRequest("wifi", "wifi_search", phone);
                            label.setVisibility(View.VISIBLE);
                            bar.setVisibility(View.VISIBLE);
                        } else {
                            //сети нет, но может всё равно отправить?
                            AlertDialog.Builder builder2 = new AlertDialog.Builder(v.getContext());
                            builder2.setMessage(R.string.no_internet_warning).setPositiveButton(R.string.yes, dialogClickListener)
                                    .setNegativeButton(R.string.no, dialogClickListener).show();
                        }
                        query.close();
                        return true;

                    case R.id.gps_id:
                        sendSmsRequest("gps", "gps_search", phone);
                        query.close();
                        label.setVisibility(View.VISIBLE);
                        bar.setVisibility(View.VISIBLE);
                        return true;

                    case R.id.del_id:
                        db.delete(dBase.PHONES_TABLE_OUT, "_id = ?", new String[] {Long.toString(num_id)});
                        //обновление списка
                        updateList();
                        query.close();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    public void set_btn_clicked(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    public void hist_btn_clicked(View view) {
        Intent intent = new Intent(this, HistoryActivity.class);
        startActivity(intent);
    }

    public void rem_btn_clicked(View view) {
        ContentValues cv = new ContentValues();
        String phone_number = text.getText().toString();
        cv.put(PHONES_COL, phone_number);

        String table;  //выбор таблицы для записи
        if (tabHost.getCurrentTab() == 0) {
            table = "phones";
        } else {
            table = "phones_to_answer";
        }

        //проверка номера на повторное вхождение
        Cursor cursor_check = db.query(table,
                new String[] {PHONES_COL},
                PHONES_COL + "=?",
                new String[] {phone_number},
                null, null, null);

        if (cursor_check.moveToFirst()) {
            Toast.makeText(MainActivity.this, R.string.phone_already_recorded, Toast.LENGTH_SHORT).show();
        } else if (phone_number.isEmpty()) {
            Toast.makeText(MainActivity.this, R.string.phone_is_empty, Toast.LENGTH_SHORT).show();
        } else {
            db.insert(table, null, cv);
            Toast.makeText(MainActivity.this, R.string.number_saved, Toast.LENGTH_SHORT).show();
        }
        text.setText("");
        cursor_check.close();

        if (tabHost.getCurrentTab() == 0) {
            updateList();
        } else {
            updateAnswList();
        }
    }

    public void sms_btn_clicked(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            //in API 19 added SMS provider
            Intent intent = new Intent(this, NewReadSms.class);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, OldReadSms.class);
            startActivity(intent);
        }
    }

    public void updateList() {
        cursor = db.query(dBase.PHONES_TABLE_OUT, null, null, null, null, null, null);
        adapter.swapCursor(cursor);
        adapter.notifyDataSetChanged();
    }

    public void updateAnswList() {
        cursor = db.query(dBase.PHONES_TABLE_IN, null, null, null, null, null, null);
        adapter_answ.swapCursor(cursor);
        adapter_answ.notifyDataSetChanged();
    }
}

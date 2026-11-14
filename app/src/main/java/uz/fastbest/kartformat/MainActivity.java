package uz.fastbest.kartformat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "uz.fastbest.kartformat.USB_PERMISSION";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private UsbMassStorage storage;
    private boolean busy;
    private boolean receiverRegistered;

    private TextView status;
    private TextView progressText;
    private ProgressBar progressBar;
    private EditText labelInput;
    private Button formatButton;
    private Button refreshButton;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    scan();
                } else {
                    status.setText("USB izni verilmedi. \"Yenile\"ye basıp izin verin.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (!busy) scan();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        status = findViewById(R.id.status);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        labelInput = findViewById(R.id.labelInput);
        formatButton = findViewById(R.id.formatButton);
        refreshButton = findViewById(R.id.refreshButton);

        formatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmFormat();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!busy) scan();
            }
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        scan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!busy) scan();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver);
        final UsbMassStorage s = storage;
        storage = null;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (s != null) s.close();
            }
        });
        worker.shutdown();
        super.onDestroy();
    }

    /** Takılı kart okuyucuyu bulur, gerekirse izin ister ve kartı açar. */
    private void scan() {
        formatButton.setEnabled(false);
        progressBar.setProgress(0);
        progressText.setText("");

        final UsbMassStorage old = storage;
        storage = null;

        UsbDevice found = null;
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (UsbMassStorage.findInterface(d) != null) {
                    found = d;
                    break;
                }
            }
        }
        if (found == null) {
            closeAsync(old);
            status.setText("Kart okuyucu bulunamadı.\n\nKartı OTG kart okuyucuya takıp "
                    + "okuyucuyu telefona bağlayın.");
            return;
        }
        if (!usb.hasPermission(found)) {
            closeAsync(old);
            status.setText("USB izni bekleniyor…");
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            usb.requestPermission(found, PendingIntent.getBroadcast(this, 0, intent, flags));
            return;
        }

        status.setText("Kart okunuyor…");
        busy = true;
        final UsbDevice device = found;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (old != null) old.close();
                try {
                    final UsbMassStorage s = UsbMassStorage.open(usb, device);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            if (isFinishing() || isDestroyed()) {
                                closeAsync(s);
                                return;
                            }
                            storage = s;
                            String name = s.name().isEmpty() ? "USB depolama" : s.name();
                            status.setText("Bulunan kart: " + name + "\nBoyut: "
                                    + sizeText(s.blockCount() * s.blockSize()));
                            formatButton.setEnabled(true);
                        }
                    });
                } catch (final IOException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            status.setText("Hata: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void closeAsync(final UsbMassStorage s) {
        if (s == null || worker.isShutdown()) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                s.close();
            }
        });
    }

    private void confirmFormat() {
        final UsbMassStorage s = storage;
        if (s == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Kart biçimlendirilsin mi?")
                .setMessage("Karttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.\n\n"
                        + "Kart: " + sizeText(s.blockCount() * s.blockSize()))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve biçimlendir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startFormat(s);
                    }
                })
                .show();
    }

    private void startFormat(final UsbMassStorage s) {
        if (busy || s != storage) return;
        busy = true;
        formatButton.setEnabled(false);
        refreshButton.setEnabled(false);
        labelInput.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("Biçimlendiriliyor… Kartı çıkarmayın.");
        final String label = labelInput.getText().toString();

        worker.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    Fat32Formatter.format(s, label, new Fat32Formatter.Progress() {
                        private int last = -1;

                        @Override
                        public void onProgress(long doneBlocks, long totalBlocks) {
                            final int pct = (int) (doneBlocks * 100 / Math.max(1, totalBlocks));
                            if (pct == last) return;
                            last = pct;
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    progressBar.setProgress(pct);
                                    progressText.setText("%" + pct);
                                }
                            });
                        }
                    });
                    // Yazılanı doğrula: bölüm tablosu geri okunabiliyor mu?
                    byte[] check = new byte[s.blockSize()];
                    s.read(0, check, 1);
                    if ((check[510] & 0xFF) != 0x55 || (check[511] & 0xFF) != 0xAA
                            || check[450] != 0x0C) {
                        error = "Yazılan veri doğrulanamadı; kart bozuk ya da sahte olabilir";
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = "Beklenmeyen hata: " + e;
                }
                final String result = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishFormat(result);
                    }
                });
            }
        });
    }

    private void finishFormat(String error) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshButton.setEnabled(true);
        labelInput.setEnabled(true);
        if (error == null) {
            progressBar.setProgress(100);
            progressText.setText("%100");
            status.setText("Tamamlandı. Kart FAT32 olarak biçimlendirildi.\n\n"
                    + "Okuyucuyu çıkarın ve kartı telefonun kart yuvasına takın.");
            closeAsync(storage);
            storage = null;
        } else {
            status.setText("Biçimlendirme başarısız: " + error
                    + "\n\nOkuyucuyu çıkarıp yeniden takın ve tekrar deneyin.");
            closeAsync(storage);
            storage = null;
        }
    }

    private static String sizeText(long bytes) {
        double gb = bytes / 1e9;
        if (gb >= 1) return String.format(Locale.ROOT, "%.1f GB", gb);
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
    }
}

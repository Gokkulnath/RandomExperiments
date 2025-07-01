package com.example.smsbackup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Telephony;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_REQUEST_CODE = 101;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button backupButton = findViewById(R.id.backupButton);
        backupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSmsPermission();
            }
        });
    }

    private void requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_SMS}, SMS_PERMISSION_REQUEST_CODE);
        } else {
            requestStoragePermission();
        }
    }

    private void requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
        } else {
            backupSms();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestStoragePermission();
            } else {
                Toast.makeText(this, "SMS permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                backupSms();
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void backupSms() {
        File csvFile = createCsvFile();
        if (csvFile == null) {
            Toast.makeText(this, "Error creating CSV file", Toast.LENGTH_SHORT).show();
            return;
        }

        try (FileWriter fileWriter = new FileWriter(csvFile)) {
            // Write CSV header
            fileWriter.append("Thread ID,Address,Person,Date,Date Sent,Protocol,Read,Status,Type,Reply Path Present,Subject,Body,Service Center,Locked,Error Code,Seen\n");

            Uri smsUri = Telephony.Sms.CONTENT_URI;
            Cursor cursor = getContentResolver().query(smsUri, null, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String threadId = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID));
                    String address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                    String person = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.PERSON));
                    long dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));
                    long dateSentMillis = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT));
                    String protocol = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.PROTOCOL));
                    String read = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.READ));
                    String status = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS));
                    String type = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE));
                    String replyPathPresent = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.REPLY_PATH_PRESENT));
                    String subject = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.SUBJECT));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                    String serviceCenter = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.SERVICE_CENTER));
                    String locked = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.LOCKED));
                    String errorCode = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ERROR_CODE));
                    String seen = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN));

                    // Format dates
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    String date = sdf.format(new Date(dateMillis));
                    String dateSent = sdf.format(new Date(dateSentMillis));

                    // Escape commas and newlines in body and subject
                    body = body != null ? body.replace("\"", "\"\"").replace("\n", " ") : "";
                    subject = subject != null ? subject.replace("\"", "\"\"").replace("\n", " ") : "";


                    fileWriter.append("\"").append(threadId).append("\",");
                    fileWriter.append("\"").append(address).append("\",");
                    fileWriter.append("\"").append(person).append("\",");
                    fileWriter.append("\"").append(date).append("\",");
                    fileWriter.append("\"").append(dateSent).append("\",");
                    fileWriter.append("\"").append(protocol).append("\",");
                    fileWriter.append("\"").append(read).append("\",");
                    fileWriter.append("\"").append(status).append("\",");
                    fileWriter.append("\"").append(type).append("\",");
                    fileWriter.append("\"").append(replyPathPresent).append("\",");
                    fileWriter.append("\"").append(subject).append("\",");
                    fileWriter.append("\"").append(body).append("\",");
                    fileWriter.append("\"").append(serviceCenter).append("\",");
                    fileWriter.append("\"").append(locked).append("\",");
                    fileWriter.append("\"").append(errorCode).append("\",");
                    fileWriter.append("\"").append(seen).append("\"\n");

                } while (cursor.moveToNext());
                cursor.close();
                Toast.makeText(this, "SMS backup successful: " + csvFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "No SMS messages found", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error writing to CSV file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private File createCsvFile() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                 // Fallback to app-specific directory if Downloads creation fails
                downloadsDir = getExternalFilesDir(null);
                if (downloadsDir == null) {
                    return null;
                }
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()){
                     return null;
                }
            }
        }
        String fileName = "sms_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".csv";
        return new File(downloadsDir, fileName);
    }
}

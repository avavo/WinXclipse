package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.winlator.cmod.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.XclipseDriverManager;
import java.util.ArrayList;

public class XclipseDriversFragment extends Fragment {
    
    private XclipseDriverManager driverManager;
    private RecyclerView recyclerView;
    
    @Override 
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.driverManager = new XclipseDriverManager(getActivity());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup)inflater.inflate(R.layout.xclipse_drivers_fragment, container, false);
        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(new DriversAdapter(driverManager.enumerateInstalledDrivers()));
        View btInstallDriver = layout.findViewById(R.id.BTInstallDriver);
        btInstallDriver.setOnClickListener((v) -> {
            ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);               
            });
        });
        return layout;
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.xclipse_gpu_drivers);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data == null || data.getData() == null) return;
            Uri uri = data.getData();
            String driver = driverManager.installDriver(uri);
            if (!driver.isEmpty())
                ((DriversAdapter)recyclerView.getAdapter()).addItem(driver);
        }
     }
    
    private class DriversAdapter extends RecyclerView.Adapter<DriversAdapter.ViewHolder> {
        private ArrayList<String> driversList;

        public class ViewHolder extends RecyclerView.ViewHolder {
            private TextView tvName;
            private TextView tvVersion;
            private ImageButton btMenu;

            public ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.TVName);
                tvVersion = v.findViewById(R.id.TVVersion);
                btMenu = v.findViewById(R.id.BTMenu);
            }
        }
        
        public DriversAdapter(ArrayList<String> driversList) {
            this.driversList = driversList;
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.xclipse_driver_list_item, viewGroup, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {
            viewHolder.tvName.setText(driverManager.getDriverName(driversList.get(position)));
            viewHolder.tvVersion.setText(driverManager.getDriverVersion(driversList.get(position)));
            viewHolder.btMenu.setOnClickListener((v) -> {
                removeAtIndex(position);
            });
        }
        
        public void addItem(String item) {
            driversList.add(item);
            notifyItemInserted(getItemCount() - 1);
        }
        
        public void removeAtIndex(int index) {
            String deletedDriver = driversList.remove(index);
            driverManager.removeDriver(deletedDriver);
            notifyItemRemoved(index);
            notifyItemRangeChanged(index, getItemCount());
        }
        
        @Override
        public int getItemCount() {
            return driversList.size();
        }
    }
}

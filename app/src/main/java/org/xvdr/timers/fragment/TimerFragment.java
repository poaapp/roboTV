package org.xvdr.timers.fragment;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v17.leanback.app.BackgroundManager;
import android.support.v17.leanback.app.BrowseFragment;
import android.support.v17.leanback.app.ProgressBarManager;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.ListRowPresenter;
import android.support.v17.leanback.widget.OnItemViewClickedListener;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowPresenter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.xvdr.jniwrap.Packet;
import org.xvdr.robotv.service.DataService;
import org.xvdr.robotv.service.DataServiceClient;
import org.xvdr.timers.activity.EpgSearchActivity;
import org.xvdr.recordings.model.Movie;
import org.xvdr.timers.activity.TimerActivity;
import org.xvdr.timers.presenter.EpgEventPresenter;
import org.xvdr.recordings.util.Utils;
import org.xvdr.robotv.R;
import org.xvdr.robotv.artwork.ArtworkFetcher;
import org.xvdr.robotv.artwork.ArtworkHolder;
import org.xvdr.robotv.artwork.Event;
import org.xvdr.robotv.client.Connection;
import org.xvdr.robotv.setup.SetupUtils;

import java.io.IOException;
import java.util.Collection;

public class TimerFragment extends BrowseFragment implements DataServiceClient.Listener {

    class EpgSearchLoader extends AsyncTask<Void, Void, ArrayObjectAdapter> {

        @Override
        protected ArrayObjectAdapter doInBackground(Void... params) {
            ArrayObjectAdapter rowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());

            if(connection == null) {
                return rowsAdapter;
            }

            Packet req = connection.CreatePacket(
                             Connection.XVDR_EPG_GETFORCHANNEL,
                             Connection.XVDR_CHANNEL_REQUEST_RESPONSE);


            req.putU32(channelUid);
            req.putU32(System.currentTimeMillis() / 1000);
            req.putU32(60 * 60 * 24);

            Packet resp = connection.transmitMessage(req);

            // check if we got a response
            if(resp == null) {
                return rowsAdapter;
            }

            // uncompress response
            resp.uncompress();

            // add new row
            ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(new EpgEventPresenter());
            HeaderItem header = new HeaderItem(channelUid, channelName);

            ListRow row = new ListRow(header, listRowAdapter);
            ArrayObjectAdapter rowAdapter = (ArrayObjectAdapter) row.getAdapter();

            rowsAdapter.add(row);

            // process result
            while(!resp.eop() && !isCancelled()) {
                int eventId = (int) resp.getU32();
                long startTime = resp.getU32();
                long endTime = startTime + resp.getU32();
                int content = (int) resp.getU32();
                int eventDuration = (int)(endTime - startTime);
                long parentalRating = resp.getU32();
                String title = resp.getString();
                String plotOutline = resp.getString();
                String plot = resp.getString();
                String posterUrl = resp.getString();
                String backgroundUrl = resp.getString();

                Event event = new Event(content, title, plotOutline, plot, eventDuration, eventId);
                ArtworkHolder art = null;

                try {
                    art = artwork.fetchForEvent(event);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }

                final Movie movie = new Movie();
                movie.setTitle(event.getTitle());
                movie.setOutline(event.getSubTitle());
                movie.setTimeStamp(event.getTimestamp().getTime());
                movie.setChannelName(channelName);
                movie.setArtwork(art);
                movie.setTimeStamp(startTime * 1000);
                movie.setChannelUid(channelUid);
                movie.setDuration(eventDuration);

                rowAdapter.add(movie);
            }

            return rowsAdapter;
        }

        @Override
        protected void onPreExecute() {
            progress.show();
            progress.enableProgressBar();
        }

        @Override
        protected void onPostExecute(final ArrayObjectAdapter result) {
            progress.disableProgressBar();
            progress.hide();

            TimerFragment.this.setAdapter(result);
            startEntranceTransition();
        }

        @Override
        protected void onCancelled(ArrayObjectAdapter result) {
            progress.disableProgressBar();
            progress.hide();

            TimerFragment.this.setAdapter(result);
            startEntranceTransition();
        }
    }

    private Connection connection;
    private ArtworkFetcher artwork;
    private String channelName;
    private int channelUid;
    private EpgSearchLoader loader;
    ProgressBarManager progress;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        prepareEntranceTransition();

        channelUid = getActivity().getIntent().getIntExtra("uid", 0);
        channelName = getActivity().getIntent().getStringExtra("name");

        int color_background = Utils.getColor(getActivity(), R.color.recordings_background);
        int color_brand = Utils.getColor(getActivity(), R.color.primary_color);

        BackgroundManager backgroundManager = BackgroundManager.getInstance(getActivity());
        backgroundManager.attach(getActivity().getWindow());
        backgroundManager.setColor(color_background);

        setTitle(getString(R.string.timer_title));
        setHeadersTransitionOnBackEnabled(true);

        setBrandColor(color_brand);
        setSearchAffordanceColor(Utils.getColor(getActivity(), R.color.recordings_search_button_color));

        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
                Movie movie = (Movie) item;
                ((TimerActivity) getActivity()).selectEvent(movie);
            }
        });

        setOnSearchClickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(getActivity(), EpgSearchActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        progress = new ProgressBarManager();
        progress.setRootView((ViewGroup) view);

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        loader = null;
    }

    @Override
    public void onServiceConnected(DataService service) {
        connection = service.getConnection();

        String language = SetupUtils.getLanguage(getActivity());
        artwork = new ArtworkFetcher(connection, language);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                setHeadersState(HEADERS_DISABLED);
                loadEpgForChannel();
            }
        });
    }

    @Override
    public void onServiceDisconnected(DataService service) {
    }

    @Override
    public void onMovieCollectionUpdated(DataService service, Collection<Movie> collection, int status) {

    }

    private void loadEpgForChannel() {
        loader = new EpgSearchLoader();
        loader.execute();
    }
}

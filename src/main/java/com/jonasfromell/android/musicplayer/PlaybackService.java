package com.jonasfromell.android.musicplayer;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by Jonas on 8/19/14.
 */
public class PlaybackService extends Service {
    private static final String TAG = "PlaybackService";

    // AudioManager to request audio focus for playback
    private AudioManager mAudioManager;

    // The MediaPlayer
    private MediaPlayer mMediaPlayer;

    // The queue
    private Queue mQueue;
    private int mCurrentQueuePosition;

    // Track the current song
    private Song mCurrentSong;

    // Keep track of state of music player
    private boolean mIsPaused = false;
    private boolean mIsStopped = false;

    // Keep track of the registered clients
    private ArrayList<Messenger> mClients = new ArrayList<Messenger>();

    // Message codes
    static final int MSG_REGISTER_CLIENT = 1;
    static final int MSG_UNREGISTER_CLIENT = 2;

    static final int MSG_PLAY_PAUSE = 3;
    static final int MSG_PLAY_NEXT = 4;
    static final int MSG_PLAY_PREVIOUS = 5;

    static final int MSG_PLAY = 6;

    static final int MSG_NOW_PLAYING = 7;
    static final int MSG_IS_PAUSED = 8;

    static final int MSG_ADD_TO_QUEUE = 9;

    // This is what we publish to the client
    final Messenger mMessenger = new Messenger(new IncomingHandler());

    // Handle incoming messages here
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage (Message msg) {
            Log.i(TAG, "Recieved message");

            switch (msg.what) {
                // Client registration
                case MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                // Music control
                case MSG_PLAY_PAUSE:
                    Log.i(TAG, "Handling PLAY_PAUSE");
                    toggle();
                    break;
                case MSG_PLAY_NEXT:
                    Log.i(TAG, "Handling PLAY_NEXT");
                    next();
                    break;
                case MSG_PLAY_PREVIOUS:
                    Log.i(TAG, "Handling PLAY_PREVIOUS");
                    previous();
                    break;
                case MSG_PLAY:
                    // TODO: Refactor this into it's own function
                    Log.i(TAG, "Handling PLAY");
                    // Get the song out of the message
                    Song playSong = msg.getData().getParcelable("Song");
                    Log.i(TAG, "Playing song: " + playSong.getTitle());
                    // Play the song
                    play(playSong);

                    // Build a new message to send to the clients
                    Message newMessage = Message.obtain(null, MSG_NOW_PLAYING);

                    Bundle data = new Bundle();
                    data.putParcelable("Song", mCurrentSong);

                    newMessage.setData(data);
                    // TODO: Refactor this into it's own function (as we will be using this a lot)
                    // Send messages back to the clients that this song is now playing
                    for (int i = mClients.size() - 1; i >= 0; i--) {
                        try {
                            mClients.get(i).send(newMessage);
                        }
                        catch (RemoteException e) {
                            // The client is dead, remove it from the list
                            mClients.remove(i);
                        }
                    }

                    break;
                case MSG_ADD_TO_QUEUE:
                    Log.i(TAG, "Handling ADD_TO_QUEUE");
                    // Get the song out of the message
                    Song queueSong = msg.getData().getParcelable("Song");
                    Log.i(TAG, "Adding song: " + queueSong.getTitle());
                    // Add the song to the queue
                    addToQueue(queueSong);

                    break;
                // Default
                default:
                    super.handleMessage(msg);
            }
        }
    }

    @Override
    public IBinder onBind (Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public void onCreate () {
        super.onCreate();

        Log.i(TAG, "Service created");

        // Initialize the audio manager
        mAudioManager = (AudioManager) getBaseContext().getSystemService(Context.AUDIO_SERVICE);

        // Initialize the media player
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        // Set media player event listeners
        mMediaPlayer.setOnPreparedListener(mOnPreparedListener);
        mMediaPlayer.setOnCompletionListener(mOnCompletionListener);
        mMediaPlayer.setOnErrorListener(mOnErrorListener);

        // Initialize a new queue
        mQueue = new Queue();
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id: " + startId);

        // We want to run this service until it is explicitly stopped
        return START_STICKY;
    }

    // Music control

    /**
     * Toggles the play or pause state
     */
    private void toggle () {
        if (mMediaPlayer.isPlaying()) {
            pause();
        }
        else {
            play();
        }
    }

    /**
     * Resumes playback if paused, or plays the first song in the queue if there is one
     */
    private void play () {
        // We don't wanna do anything if we are currently playing
        if (!mMediaPlayer.isPlaying()) {
            // If playback is currently paused, just resume playback
            if (mIsPaused) {
                mIsPaused = false;

                mMediaPlayer.start();
            }
            // Playback has not yet begun, so if we have queued songs, play the first one
            else {
                if (mQueue.length() > 0) {
                    play(mQueue.getFirst());
                }
            }
        }
    }

    /**
     * Starts playback
     *
     * @param song Song
     */
    private void play (Song song) {
        // Reset the media player
        mMediaPlayer.reset();

        // Try to set the data source
        try {
            mMediaPlayer.setDataSource(song.getFilepath());
        }
        catch (IOException e) {
            Log.e(TAG, "Couldn't read file");
        }

        // Store the current song
        mCurrentSong = song;

        // Store the current position in the queue
        mCurrentQueuePosition = mQueue.indexOf(song);

        // Prepare the media player
        mMediaPlayer.prepareAsync();
    }

    /**
     * Pauses playback
     */
    private void pause () {
        mIsPaused = true;

        mMediaPlayer.pause();

        // TODO: Broadcast to clients
    }

    /**
     * Stops playback
     */
    private void stop () {
        mMediaPlayer.stop();
    }

    /**
     * Plays the next track
     */
    private void next () {
        Song nextSong = mQueue.getNext(mCurrentQueuePosition);

        // If no next song is found, stop playback
        if (nextSong != null) {
            play(nextSong);
        }
        else {
            stop();
        }
    }

    /**
     * Plays the previous track
     */
    private void previous () {
        Song previousSong = mQueue.getPrevious(mCurrentQueuePosition);

        // If no previous song is found, just replay the current song
        if (previousSong != null) {
            play(previousSong);
        }
        else {
            replay();
        }
    }

    /**
     * Re-plays the current track
     */
    private void replay () {
        play(mCurrentSong);
    }

    // Manage queue

    /**
     * Adds a track to the queue
     *
     * @param song
     */
    private void addToQueue (Song song) {
        mQueue.add(song);
    }

    /**
     * Adds several tracks to the queue
     *
     * @param songs
     */
    private void addToQueue (ArrayList<Song> songs) {
        mQueue.add(songs);
    }

    /**
     * Remove track from the queue
     *
     * @param song
     */
    private void removeFromQueue (Song song) {
        mQueue.remove(song);
    }

    /**
     * MediaPlayer.OnComplete listener
     */
    private MediaPlayer.OnCompletionListener mOnCompletionListener = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion (MediaPlayer mediaPlayer) {
            Log.i(TAG, "MediaPlayer completed playback");

            // Play the next song in the queue
            next();
        }
    };

    /**
     * MediaPlayer.OnError listener
     */
    private MediaPlayer.OnErrorListener mOnErrorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError (MediaPlayer mediaPlayer, int i, int i2) {
            Log.i(TAG, "MediaPlayer threw an error");
            return false;
        }
    };

    private MediaPlayer.OnPreparedListener mOnPreparedListener = (new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared (MediaPlayer mediaPlayer) {
            Log.i(TAG, "MediaPlayer is prepared");
            // Make sure paused is set to false
            mIsPaused = false;

            mediaPlayer.start();
        }
    });

    /**
     * Listen for changes in audio focus
     */
    private AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange (int i) {
            if (i == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause();
            }
            else if (i == AudioManager.AUDIOFOCUS_GAIN) {
                play();
            }
            else if (i == AudioManager.AUDIOFOCUS_LOSS) {
                mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);

                stop();
            }
        }
    };

    /**
     * Intent filter for the AUDIO_BECOMING_NOISY broadcast
     */
    private IntentFilter mNoisyAudioIntentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    /**
     * Listen for the AUDIO_BECOMING_NOISY broadcast and pause playback if it is.
     */
    private class NoisyAudioStreamReceiver extends BroadcastReceiver {
        @Override
        public void onReceive (Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    }
}

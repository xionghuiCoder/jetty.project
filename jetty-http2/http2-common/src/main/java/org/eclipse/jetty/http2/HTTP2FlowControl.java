//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2FlowControl implements FlowControl
{
    private static final Logger LOG = Log.getLogger(HTTP2FlowControl.class);

    private int initialStreamWindow;

    public HTTP2FlowControl(int initialStreamWindow)
    {
        this.initialStreamWindow = initialStreamWindow;
    }

    @Override
    public void onNewStream(IStream stream)
    {
        stream.updateSendWindow(initialStreamWindow);
        stream.updateRecvWindow(FlowControl.DEFAULT_WINDOW_SIZE);
    }

    @Override
    public void updateInitialStreamWindow(ISession session, int initialStreamWindow)
    {
        int initialWindow = this.initialStreamWindow;
        this.initialStreamWindow = initialStreamWindow;
        int delta = initialStreamWindow - initialWindow;

        // SPEC: updates of the initial window size only affect stream windows, not session's.
        for (Stream stream : session.getStreams())
            session.onWindowUpdate((IStream)stream, new WindowUpdateFrame(stream.getId(), delta));
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
        int delta = frame.getWindowDelta();
        if (frame.getStreamId() > 0)
        {
            // The stream may have been reset concurrently.
            if (stream != null)
            {
                int oldSize = stream.updateSendWindow(delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated stream send window {} -> {} for {}", oldSize, oldSize + delta, stream);
            }
        }
        else
        {
            int oldSize = session.updateSendWindow(delta);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated session send window {} -> {} for {}", oldSize, oldSize + delta, session);
        }
    }

    @Override
    public void onDataReceived(ISession session, IStream stream, int length)
    {
        int oldSize = session.updateRecvWindow(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Updated session recv window {} -> {} for {}", oldSize, oldSize - length, session);

        if (stream != null)
        {
            oldSize = stream.updateRecvWindow(-length);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated stream recv window {} -> {} for {}", oldSize, oldSize - length, stream);
        }
    }

    @Override
    public void onDataConsumed(IStream stream, int length)
    {
        // This is the algorithm for flow control.
        // This method is called when a whole flow controlled frame has been consumed.
        // We currently send a WindowUpdate every time, even if the frame was very small.
        // Other policies may send the WindowUpdate only upon reaching a threshold.

        if (LOG.isDebugEnabled())
            LOG.debug("Data consumed, increasing windows by {} for {}", length, stream);

        if (length > 0)
        {
            ISession session = stream.getSession();
            session.updateRecvWindow(length);
            stream.updateRecvWindow(length);

            // Negative streamId allow for generation of bytes for both stream and session.
            WindowUpdateFrame frame = new WindowUpdateFrame(-stream.getId(), length);
            session.control(stream, Callback.Adapter.INSTANCE, frame, Frame.EMPTY_ARRAY);
        }
    }

    @Override
    public void onDataSending(IStream stream, int length)
    {
        if (length == 0)
            return;

        ISession session = stream.getSession();
        int oldSize = session.updateSendWindow(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Updated session send window {} -> {} for {}", oldSize, oldSize - length, session);

        oldSize = stream.updateSendWindow(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Updated stream send window {} -> {} for {}", oldSize, oldSize - length, stream);
    }

    @Override
    public void onDataSent(IStream stream, int length)
    {
    }

    @Override
    public void onSessionStalled(ISession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session stalled {}", session);
    }

    @Override
    public void onStreamStalled(IStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stream stalled {}", stream);
    }
}

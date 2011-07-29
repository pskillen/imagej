//
// ShowSubscribers.java
//

/*
ImageJ software for multidimensional image processing and analysis.

Copyright (c) 2010, ImageJDev.org.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the names of the ImageJDev.org developers nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
*/

package imagej.ui.swing.plugins.debug;

import imagej.display.event.DisplayActivatedEvent;
import imagej.display.event.DisplayUpdatedEvent;
import imagej.event.EventSubscriber;
import imagej.event.Events;
import imagej.ext.plugin.ImageJPlugin;
import imagej.ext.plugin.Plugin;
import imagej.object.event.ObjectCreatedEvent;
import imagej.object.event.ObjectDeletedEvent;
import imagej.object.event.ObjectsChangedEvent;
import imagej.ui.swing.StaticSwingUtils;
import imagej.ui.swing.SwingOutputWindow;

import java.util.List;

/**
 * For EventBus diagnostics...
 * Shows what is subscribed to a event types...  
 * 
 * Change/Add event types as necessary...
 * 
 * @author Grant Harris
 */
@Plugin(menuPath = "Plugins>Debug>Subscribers")
public class ShowSubscribers implements ImageJPlugin {

	private static SwingOutputWindow window;
	private List<EventSubscriber<?>> subscribers;

	@Override
	public void run() {
		window = new SwingOutputWindow("Subscribers");
		StaticSwingUtils.locateLowerRight(window);
		listSubs(ObjectsChangedEvent.class);
		listSubs(ObjectCreatedEvent.class);
		listSubs(ObjectDeletedEvent.class);
		listSubs(DisplayActivatedEvent.class);
		listSubs(DisplayUpdatedEvent.class);
		
	}

	private void listSubs(Class clazz) {
		subscribers = Events.getSubscribers(clazz);
		window.append(clazz.getSimpleName() + ":\n");
		for (EventSubscriber<?> subscriber : subscribers) {
			window.append("    " + subscriber.toString() + "\n");
		}
	}

}

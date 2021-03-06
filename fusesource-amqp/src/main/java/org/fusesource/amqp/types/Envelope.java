/**
 * Copyright (C) 2012 FuseSource Corp. All rights reserved.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.amqp.types;

import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.DataByteArrayOutputStream;
import org.fusesource.amqp.types.*;

import java.io.DataOutput;

/**
 *
 */
public class Envelope {

    protected Header header;
    protected DeliveryAnnotations deliveryAnnotations;
    protected MessageAnnotations messageAnnotations;
    protected Message message;
    protected Footer footer;

    public Buffer toBuffer() throws Exception {
        int size = (int) size();
        if ( size == 0 ) {
            return new Buffer(0);
        }
        Buffer buf = new Buffer(size);
        DataByteArrayOutputStream out = new DataByteArrayOutputStream(size);
        write(out);
        return out.toBuffer();
    }

    public void write(DataOutput out) throws Exception {
        if ( header != null ) {
            header.write(out);
        }
        if ( deliveryAnnotations != null ) {
            deliveryAnnotations.write(out);
        }
        if ( messageAnnotations != null ) {
            messageAnnotations.write(out);
        }
        if ( message != null ) {
            MessageSupport.write(message, out);
        }
        if ( footer != null ) {
            footer.write(out);
        }
    }

    public long size() {
        long rc = 0;
        if ( header != null ) {
            rc += header.size();
        }
        if ( deliveryAnnotations != null ) {
            rc += deliveryAnnotations.size();
        }
        if ( messageAnnotations != null ) {
            rc += messageAnnotations.size();
        }
        if ( message != null ) {
            rc += MessageSupport.size(message);
        }
        if ( footer != null ) {
            rc += footer.size();
        }
        return rc;
    }

    public String toString() {
        StringBuffer buf = new StringBuffer();
        if ( header != null ) {
            buf.append("\n");
            buf.append(header.toString());
        }
        if ( deliveryAnnotations != null ) {
            buf.append("\n");
            buf.append(deliveryAnnotations.toString());
        }
        if ( messageAnnotations != null ) {
            buf.append("\n");
            buf.append(messageAnnotations.toString());
        }
        if ( message != null ) {
            buf.append("\n");
            buf.append(message.toString());
        }
        if ( footer != null ) {
            buf.append("\n");
            buf.append(footer.toString());
        }
        buf.append("\n");
        return buf.toString().trim();
    }

    public Header getHeader() {
        return header;
    }

    public void setHeader(Header header) {
        this.header = header;
    }

    public DeliveryAnnotations getDeliveryAnnotations() {
        return deliveryAnnotations;
    }

    public void setDeliveryAnnotations(DeliveryAnnotations deliveryAnnotations) {
        this.deliveryAnnotations = deliveryAnnotations;
    }

    public MessageAnnotations getMessageAnnotations() {
        return messageAnnotations;
    }

    public void setMessageAnnotations(MessageAnnotations messageAnnotations) {
        this.messageAnnotations = messageAnnotations;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public Footer getFooter() {
        return footer;
    }

    public void setFooter(Footer footer) {
        this.footer = footer;
    }
}

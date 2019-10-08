/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2019 jPOS Software SRL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.iso.packager;

import org.jpos.iso.*;
import org.jpos.util.LogEvent;
import org.jpos.util.Logger;
import org.xml.sax.Attributes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 *
 * Packager for fields containing TLV sub-fields without a bitmap
 *
 * The Tag is alphanumeric so a mapping between fieldNumber and tag are required. A TagMapper
 * implementation should provide this mapping
 *
 */
public class GenericTaggedFieldsPackager extends GenericPackager
    implements ISOSubFieldPackager {

    private TagMapper tagMapper = null;
    private Integer fieldId = 0;

    public GenericTaggedFieldsPackager() throws ISOException {
        super();
    }

    @Override
    public int getFieldNumber() {
      return fieldId;
    }

    private Set<Integer> selectFields() {
        Map<Integer, ISOFieldPackager> fldMap = new ConcurrentHashMap<>();
        IntStream.range(getFirstField(), fld.length)
                .filter(i -> fld[i] != null)
                .forEach(i -> fldMap.put(i, fld[i]));

        return fldMap.keySet();
    }

    private int unpackTag(Set<Integer> flds, ISOComponent m, byte[] b, int consumed)
            throws ISOException {

        for (Integer i : flds) {
            ISOFieldPackager fpack = fld[i];
            ISOComponent c = fpack.createComponent(i);
            int unpacked = fpack.unpack(c, b, consumed);
            if (unpacked > 0) {
                m.set(c);
                // for optimize subsequent iterations
                flds.remove(i);
                return unpacked;
            }
        }

        // undefined tag - all defined fields was tested
        return 0;
    }

    @Override
    public int unpack(ISOComponent m, byte[] b) throws ISOException {
        LogEvent evt = new LogEvent(this, "unpack");
        try {
            if (m.getComposite() != m)
                throw new ISOException("Can't call packager on non Composite");
            if (b.length == 0)
                return 0; // nothing to do
            if (logger != null)  // save a few CPU cycle if no logger available
                evt.addMessage(ISOUtil.hexString(b));

            int consumed = 0;
            Set<Integer> flds = selectFields();
            for (int i : flds) {
                if (consumed >= b.length)
                    break;

                consumed += unpackTag(flds, m, b, consumed);
            }
            if (b.length != consumed) {
                evt.addMessage(
                        "WARNING: unpack len=" + b.length + " consumed=" + consumed);
            }
            return consumed;
        } catch (ISOException e) {
            evt.addMessage(e);
            throw e;
        } catch (Exception e) {
            evt.addMessage(e);
            throw new ISOException(e);
        } finally {
            Logger.log(evt);
        }
    }

    @Override
    public void unpack(ISOComponent m, InputStream in) {
        // This method is not called anywhere so is usless
        throw new UnsupportedOperationException("The on InputStream is not supported");
    }

    /**
     * Pack the subfield into a byte array
     *
     * @return packed array of bytes
     * @throws org.jpos.iso.ISOException
     */
    @Override
    public byte[] pack(ISOComponent m) throws ISOException {
        LogEvent evt = new LogEvent(this, "pack");
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream(100)) {
            ISOComponent c;
            Map fields = m.getChildren();

            for (int i = getFirstField(); i <= m.getMaxField(); i++) {
                c = (ISOComponent) fields.get(i);
                if (c != null) {
                    try {
                        byte[] b = fld[i].pack(c);
                        bout.write(b);
                    } catch (Exception e) {
                        evt.addMessage("error packing subfield " + i);
                        evt.addMessage(c);
                        evt.addMessage(e);
                        throw e;
                    }
                }
            }

            byte[] d = bout.toByteArray();
            if (logger != null)  // save a few CPU cycle if no logger available
                evt.addMessage(ISOUtil.hexString(d));
            return d;
        } catch (ISOException ex) {
            evt.addMessage(ex);
            throw ex;
        } catch (Exception ex) {
            evt.addMessage(ex);
            throw new ISOException(ex);
        } finally {
            Logger.log(evt);
        }
    }

    @Override
    public void setFieldPackager(ISOFieldPackager[] fld) {
        super.setFieldPackager(fld);
        for (ISOFieldPackager aFld : fld) {
            TaggedFieldPackagerBase tfp = null;
            if (aFld instanceof TaggedFieldPackagerBase)
                tfp = (TaggedFieldPackagerBase) aFld;
            else if(aFld instanceof ISOMsgFieldPackager) {
                ISOMsgFieldPackager fp = (ISOMsgFieldPackager) aFld;
                if(fp.getISOFieldPackager() instanceof TaggedFieldPackagerBase)
                    tfp = (TaggedFieldPackagerBase) fp.getISOFieldPackager();
            }
            if (tfp != null) {
                tfp.setParentFieldNumber(fieldId);
                tfp.setTagMapper(tagMapper);
                tfp.setPackingLenient(isPackingLenient());
                tfp.setUnpackingLenient(isUnpackingLenient());
            }
        }
    }

    @Override
    protected void setGenericPackagerParams(Attributes atts) {
        super.setGenericPackagerParams(atts);
        try {
            Class<? extends TagMapper> clazz = Class.forName(atts.getValue("tagMapper")).asSubclass(TagMapper.class);
            tagMapper = clazz.newInstance();
            fieldId = Integer.parseInt(atts.getValue("id"));
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Subclasses may override this method if a lenient packing is required when a
     * field-to-tag mapping cannot be found.
     *
     * @return A boolean value for or against lenient packing
     */
    protected boolean isPackingLenient() {
        return false;
    }

    /**
     * Subclasses may override this method if a lenient unpacking is required when a
     * tag-to-field mapping cannot be found.
     *
     * @return A boolean value for or against lenient unpacking
     */
    protected boolean isUnpackingLenient() {
        return false;
    }

}



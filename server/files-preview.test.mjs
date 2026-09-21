import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {deflateRawSync} from 'node:zlib';
const source=readFileSync(new URL('./files-preview.js.txt',import.meta.url),'utf8');
const {readDocxXml}=await import('data:text/javascript;base64,'+Buffer.from(source+'\nexport {readDocxXml};').toString('base64'));
function docx(text,method=8,claimed=null){
 const xml=Buffer.from(text),data=method===8?deflateRawSync(xml):xml,name=Buffer.from('word/document.xml');
 const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50);local.writeUInt16LE(method,8);local.writeUInt32LE(data.length,18);local.writeUInt32LE(claimed??xml.length,22);local.writeUInt16LE(name.length,26);
 const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50);central.writeUInt16LE(method,10);central.writeUInt32LE(data.length,20);central.writeUInt32LE(claimed??xml.length,24);central.writeUInt16LE(name.length,28);
 const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(central.length+name.length,12);end.writeUInt32LE(local.length+name.length+data.length,16);
 return Uint8Array.from(Buffer.concat([local,name,data,central,name,end])).buffer;
}
const xml='<w:document xmlns:w="word"><w:p><w:r><w:t>Document românesc &amp; fotografii</w:t></w:r></w:p></w:document>';
test('DOCX reads stored and deflated document text without executing markup',async()=>{for(const m of [0,8])assert.equal(await readDocxXml(docx(xml,m)),xml);});
test('DOCX rejects truncation, encrypted entries, XML entities and oversized decompression',async()=>{
 await assert.rejects(readDocxXml(docx(xml).slice(0,-5)));
 const encrypted=docx(xml);new DataView(encrypted).setUint16(6,1,true);await assert.rejects(readDocxXml(encrypted));
 await assert.rejects(readDocxXml(docx('<!DOCTYPE doc [<!ENTITY x SYSTEM "https://example.invalid/">]><doc>&x;</doc>')));
 await assert.rejects(readDocxXml(docx('a'.repeat(4*1024*1024+1))));
 await assert.rejects(readDocxXml(docx('a'.repeat(10000),8,10)));
});
test('DOCX rejects mismatched local name and central offsets',async()=>{
 const file=docx(xml);new Uint8Array(file)[30]=0x58;await assert.rejects(readDocxXml(file));
 const offset=docx(xml);new DataView(offset).setUint32(offset.byteLength-6,0xffffffff,true);await assert.rejects(readDocxXml(offset));
});

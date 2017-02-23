OnsetBuf {

	var <>in;
	var <server;
	var <>defBufLen;
	var <buffer;
	var <list;
	var <synth;
	var <oscresp;
	var <isRecording = false;

	*new {arg in=0, server, defBufLen=10;
		^super.newCopyArgs(in, server, defBufLen).init;
	}

	init {
		server ?? {server = Server.default};
		list = List.new(30);
	}

	rec {arg clock, quant=0;
		if (isRecording.not) {
			if (clock.notNil) {
				clock.play({
					this.pr_startRec;
				}, quant);
			} {
				this.pr_startRec;
			};
			isRecording = true;
		}
	}

	stopRec {
		if (isRecording) {
			this.pr_freeResources;
			isRecording = false;
		}
	}

	pr_startRec {
		var buf = Buffer.alloc(
			server,
			server.sampleRate * defBufLen,
			2,
			{arg bfr; synth = Synth('onset-buf-rec', ['buf', bfr, 'in', in, 'thr', 0.05])}
		);
		this.pr_addOSC;
		buffer = buf;
	}

	pr_addOSC {
		oscresp = OSCFunc({arg msg;
			if (msg[2] == 99) {
				list.add(msg[3]);
				msg.postln;
			}
		}, '\tr', server.addr);
	}

	pr_freeResources {
		synth.release;
		oscresp.free;
	}

	free {
		this.stopRec;
		buffer.free;
		oscresp.free;
	}

	// reads a file into a buffer and analyzes it
	read {arg path;
		buffer = Buffer.read(
			server,
			Document.standardizePath(path),
			action: {arg bf;
				synth = Synth('offline-analyze', ['buf', bf, 'thr', 0.05]);
				"analyzing buffer".postln;
				this.pr_addOSC;
				OSCFunc({
					"done!".postln;
					oscresp.free;
				}, '/n_end', server.addr, nil, [synth.nodeID]).oneShot;
		});

	}

	*initClass {
		ServerBoot.add({
			SynthDef('onset-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.1), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);

				SendTrig.kr(
					Coyote.kr(Mix.new(sig),
						fastMul:0.6,
						thresh:thr),
					99,
					phase
				);
			}).add;
			SynthDef('offline-analyze', {arg buf, thr=0.05;
				var sig, phase;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				sig = FreeSelfWhenDone.kr(BufRd.ar(2, buf, phase, 0));

				SendTrig.kr(
					Coyote.kr(Mix.new(sig),
						fastMul:0.6,
						thresh:thr),
					99,
					phase
				);
			}).add;
			SynthDef('simple-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.05), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);
			}).add;
		});
	}
}


// A player that plays, shuffles and bends one OnsetBuf
OnsetBufPlayer {

	var <obj; // todo: make nice setter for obj swap
	var <>teiler;  // 1/teiler=dur. Default 16th notes
	var <server;
	var <out;
	var <>numBars;
	var <>defaultQuant;
	var <durs;
	var <amps;
	var <globalAmp;
	var <decays;
	var <pos;
	var <proxy;
	var <pbinds; // one for simple one for grain playing. Can be swapped
	var <group; // put the synths here for better control while playing


	*new  {arg obj, type=\simple, teiler=4, numBars=1, defaultQuant=4, server, out=0;
		^super.newCopyArgs(obj, teiler, server, out, numBars, defaultQuant).init(type);
	}

	init { arg tp, mp;
		server ?? {server = Server.default};
		group = Group.new;
		amps = PatternProxy(Pseq(1.0.dup(obj.list.size), inf));
		amps.quant = defaultQuant; // so the changes apply at next "bar"
		globalAmp = PatternProxy(1.0);
		durs = PatternProxy(1/teiler);
		durs.quant = defaultQuant; // changes apply at next bar
		pos = PatternProxy(Pseq(obj.list, inf));
		decays = PatternProxy(0.1);
		pbinds = (
			simple: Pbind (
				'instrument', 'simpleplayer',
				'group', group,
				'out', out,
				'dur', defaultQuant * numBars,
				'legato', 1,
				'amp', globalAmp,
				'buf', obj.buffer,
				'dec', decays // will do nothing with simpleplayer
			),
			grain: Pbind (
				'instrument', 'grainplayer',
				'group', group,
				'out', out,
				'dur', durs,
				'amp', amps * globalAmp,
				'buf', obj.buffer,
				'pos', pos,
				'dec', decays
			)
		);

		proxy = EventPatternProxy.new.quant_(defaultQuant);
		this.swap(tp);
	}

	// change the Pbind (todo: make safer)
	swap {arg type;
		proxy.source = pbinds.at(type);
	}

	play {arg clock, quant=0;
		clock = clock ?? {TempoClock.default};
		proxy.play(clock, quant:quant);
	}

	stop {
		proxy.stop;
	}

	// silence but keep rhythm?
	hush {
		globalAmp.source = 0.0;
		group.set('amp', 0.0);
	}

	on {
		globalAmp.source = 1.0;
		group.set('amp', 1.0);
	}

	// make a rhythm. Only works if grain is playing
	rh {arg numBeats ... args;
		var arr;
		args = ((args - 1) * teiler).asFloat;
		arr = Array.fill(teiler * numBeats, {arg i;
			if (args.includes(i.asFloat)) {
				1.0
			} {
				0.0
			};
		});
		this.setAmps(arr);
		^arr; // return new array
	}

	// synchronise pos with amps to create sequence that repeats exactly
	sync {
		if (pos.source.list.size < amps.source.list.size) {
			pos.source.list = pos.source.list.wrapExtend(amps.source.list.size);
		} {
			if (pos.source.list.size > amps.source.list.size) {
				pos.source.list = pos.source.list.copyRange(0, amps.source.list.size -1);
			}
		}
	}

	// resets the pos array to original
	unsync {
		pos.source.list = obj.list;
	}

	// shuffles the pos array and returns the new array
	shuf {
		var arr = pos.source.list.scramble;
		this.setPos(arr);
		^arr;
	}

	setPos {arg arr;
		pos.source = Pseq(arr, inf);
	}

	setAmps {arg arr;
		amps.source = Pseq(arr, inf); // set the source of PatternProxy
	}

	dec {arg pat=0.1;
		decays.source = pat;
	}

	free {
		this.stop;
		group.free;
	}

	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('simpleplayer', {arg buf, pos=0, gate=1, amp=1.0, out=0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(out, sig);
			}).add;

			// grain player with auto release
			SynthDef('grainplayer', {arg buf, pos=0, att=0.01, dec=0.3, amp=1.0, out=0;
				var env, sig, phase;
				env = EnvGen.ar(Env.perc(att, dec), 1, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(out, sig);
			}).add;
		});
	}
}
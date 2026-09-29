const assert = require('node:assert/strict');
const fs = require('node:fs');

async function main() {
  const sdk = await import('@easy1staking/cip113-sdk-ts');
  const ceremony = await import('./.deploy-build/deployment/ceremony.js');
  const deploy = await import('./.deploy-build/deployment/deploy.js');
  const { resolveMultisig } = await import('./.deploy-build/deployment/multisig.js');
  const { transactionHash } = await import('./.deploy-build/tx/hash.js');
  const blueprint = JSON.parse(fs.readFileSync('./node_modules/@easy1staking/cip113-sdk-ts/blueprints/standard/v0.0.1/plutus.json'));
  const address = sdk.EvoAddress.fromHex('60' + '11'.repeat(28));
  const bech32 = sdk.EvoAddress.toBech32(address);
  const hash = c => c.repeat(64);
  const utxo = (c, index = 0n, extras = {}) => ({ transactionId: sdk.EvoTransactionHash.fromHex(hash(c)), index,
    address, assets: sdk.outputAssets(200_000_000n), ...extras });
  const a = utxo('a'), b = utxo('b', 2n), c = utxo('c', 3n), fee = utxo('d');
  const token = utxo('e', 0n, { assets: sdk.outputAssets(3_000_000n, new Map([['f'.repeat(56) + 'aa', 1n]])) });
  const datum = utxo('f', 0n, { datumOption: {} }), script = utxo('0', 0n, { scriptRef: {} });
  const refs = ceremony.selectSeedUtxos([token, datum, script, a, a, b, c]);
  assert.deepEqual(refs, {paramsSeed:{txHash:hash('a'),outputIndex:0},
    issuanceSeed:{txHash:hash('b'),outputIndex:2},multisigSeed:{txHash:hash('c'),outputIndex:3}});
  assert.equal(ceremony.selectSeedUtxos([a,a,b]),null);
  assert.deepEqual(ceremony.selectSeedUtxos([utxo('1',0n,{assets:sdk.outputAssets(400_000_000n)}),c,b,a]),refs);
  assert.equal(ceremony.resolveSeeds([a,b,c],[refs.paramsSeed])[0],a);
  assert.throws(()=>ceremony.resolveSeeds([a,b,c],[refs.paramsSeed,refs.paramsSeed]),/distinct/);
  assert.throws(()=>ceremony.resolveSeeds([a,b],[refs.multisigSeed]),/missing/);
  assert.throws(()=>ceremony.resolveSeeds([token],[ceremony.utxoRef(token)]),/only ADA/);
  assert.throws(()=>ceremony.utxoRef(utxo('a',-1n)),/invalid/);
  assert.throws(()=>ceremony.utxoRef(utxo('a',BigInt(Number.MAX_SAFE_INTEGER)+1n)),/invalid/);
  assert.deepEqual(ceremony.availableFunding([a,b,c,fee,token,script,datum],Object.values(refs)),[fee]);
  console.log('PASS SDK seed conversion, distinct/plain selection, exact resolution and funding exclusions');

  const multisig=resolveMultisig(['1'.repeat(56)],1);
  const plan=ceremony.buildPlan({blueprint,networkId:0,seeds:{protocolParams:refs.paramsSeed,
    issuance:refs.issuanceSeed,upgradeMultisig:refs.multisigSeed},alwaysFailNonce:'abcd',
    maxInlineDatumBytes:1024n,unfracking:'enabled'});
  const settings={network:'preview',changeAddress:bech32,seeds:refs,members:multisig.members.map(m=>m.keyHash),
    threshold:1,maxInlineDatumBytes:1024,alwaysFailNonce:'abcd',unfrackingEnabled:true,blueprintSha256:'f'.repeat(64)};
  let walletRows=[a,b,c,fee];
  const calls=[];
  const cbor='84a300d901028001800201a0f5f6';
  const tx=new Proxy({}, {get:(_,name)=>name==='build'?async options=>{
    calls.push(['build',options]);return {toTransaction:async()=>sdk.EvoTransaction.fromCBORHex(cbor)};
  } : value=>{calls.push([name,value]);return tx}});
  const client={getUtxos:async()=>walletRows,newTx:()=>tx,
    getProtocolParameters:async()=>({coinsPerUtxoByte:4310n,maxTxSize:16384})};
  const planned={plan,settings,multisig,ctx:{client,changeAddress:bech32,availableUtxos:[]},verification:{ok:true,checks:[],mismatches:[]}};
  const built=await deploy.buildDeploymentStep(planned,'upgrade multisig');
  assert.equal(built.unsignedCbor,cbor);
  assert.deepEqual(calls.find(([n])=>n==='collectFrom')[1].inputs,[c]);
  assert.deepEqual(calls.find(([n])=>n==='build')[1].availableUtxos,[fee]);
  assert.equal(sdk.EvoAddress.toBech32(calls.find(([n])=>n==='build')[1].changeAddress),bech32);
  const nextFee=utxo('9');walletRows=[a,b,nextFee];calls.length=0;
  await deploy.buildDeploymentStep(planned,'register credentials');
  assert.deepEqual(calls.find(([n])=>n==='build')[1].availableUtxos,[nextFee]);
  const config={utxo:{...utxo('8'),address:sdk.EvoAddress.fromBech32(plan.addresses.upgradeMultisig)},ref:{txHash:hash('8'),outputIndex:0}};
  calls.length=0;
  await deploy.buildDeploymentStep(planned,'protocol genesis',config);
  assert.deepEqual(calls.find(([n])=>n==='collectFrom')[1].inputs,[a,b]);
  assert.equal(calls.find(([n])=>n==='readFrom')[1].referenceInputs[0],config.utxo);
  assert.deepEqual(calls.find(([n])=>n==='build')[1].availableUtxos,[nextFee]);
  walletRows=[nextFee];calls.length=0;
  await deploy.buildDeploymentStep(planned,'reference scripts');
  assert.deepEqual(calls.find(([n])=>n==='build')[1].availableUtxos,[nextFee]);
  const assembled=deploy.assembleDeploymentParams(plan,{protocolGenesisTxHash:hash('6'),referenceScriptsTxHash:hash('7'),multisigConfigUtxo:config.ref});
  assert.deepEqual(assembled.upgradeMultisig.utxo,config.ref);
  walletRows=[a,b,c];await assert.rejects(deploy.buildDeploymentStep(planned,'upgrade multisig'),/unreserved/);
  console.log('PASS sequential builders, exact seed objects, fresh funding, config output and record reference');

  const checkpoint=()=>({version:1,settings:structuredClone(settings),steps:[]});
  const storage={value:null,getItem(){return this.value},setItem(_,v){this.value=v}};
  const cp=checkpoint();const persist=v=>deploy.saveCheckpoint(storage,v);
  const step=deploy.saveBuiltStep(cp,built,persist);
  assert.equal(step.txHash,transactionHash(cbor));
  assert.throws(()=>deploy.saveBuiltStep(cp,{label:'register credentials',unsignedCbor:cbor},persist),/Confirm/);
  const events=[];
  const wallet={signTx:async tx=>{events.push('sign');return tx},submitTx:async()=>{events.push('submit');return step.txHash}};
  await deploy.submitSavedStep(step,{wallet,persist:()=>{events.push(step.status);persist(cp)},check:async()=> 'CONFIRMED'});
  assert.deepEqual(events,['BUILT','sign','SUBMITTING','submit','CONFIRMED']);
  assert.equal(deploy.readCheckpoint(storage,'preview').steps[0].status,'CONFIRMED');
  assert.throws(()=>deploy.saveCheckpoint({getItem:()=>null,setItem:()=>{}},cp),/save/);
  storage.value=JSON.stringify({...cp,steps:[{...step,txHash:hash('0')}]});
  assert.throws(()=>deploy.readCheckpoint(storage,'preview'),/invalid/);
  const uncertain=checkpoint();const u=deploy.saveBuiltStep(uncertain,built,persist);let submits=0,signs=0;
  const uncertainWallet={signTx:async tx=>{signs++;return tx},submitTx:async()=>{submits++;throw new Error('response lost')}};
  await assert.rejects(deploy.submitSavedStep(u,{wallet:uncertainWallet,persist:()=>persist(uncertain),check:async()=> 'CONFIRMED'}),/response lost/);
  assert.equal(deploy.readCheckpoint(storage,'preview').steps[0].status,'SUBMITTING');
  await assert.rejects(deploy.submitSavedStep(u,{wallet:uncertainWallet,persist:()=>persist(uncertain),check:async()=> 'UNKNOWN',attempts:1}),/unknown/);
  assert.equal(submits,1);assert.equal(signs,1);
  await deploy.reconcileCheckpoint(uncertain,persist,async()=> 'CONFIRMED');assert.equal(u.status,'CONFIRMED');
  const blocked=checkpoint();const bs=deploy.saveBuiltStep(blocked,built,()=>{});let broadcast=false;
  await assert.rejects(deploy.submitSavedStep(bs,{wallet:{signTx:async tx=>tx,submitTx:async()=>{broadcast=true;return ''}},
    persist:()=>{if(bs.status==='SUBMITTING')throw new Error('storage full')},check:async()=> 'CONFIRMED'}),/storage full/);
  assert.equal(broadcast,false);assert.equal(bs.status,'SUBMITTING');
  const invalid=checkpoint();const bad=deploy.saveBuiltStep(invalid,built,()=>{});
  await assert.rejects(deploy.submitSavedStep(bad,{wallet:{signTx:async tx=>tx,submitTx:async()=>bad.txHash},persist:()=>{},check:async()=> 'INVALID'}),/failed script execution/);
  assert.equal(bad.status,'INVALID');
  const changed=checkpoint();const ch=deploy.saveBuiltStep(changed,built,()=>{});
  await assert.rejects(deploy.submitSavedStep(ch,{wallet:{signTx:async()=> '84a300d901028001800202a0f5f6',submitTx:async()=>{throw new Error('must not submit')}},persist:()=>{},check:async()=> 'CONFIRMED'}),/Wallet changed/);
  console.log('PASS durable crash boundary, unknown no replay, failed persistence and invalid execution');
  const originalFetch=global.fetch;
  try{
    global.fetch=async()=>({status:404});assert.equal(await deploy.checkDeploymentTransaction('preview',hash('a')),'UNKNOWN');
    global.fetch=async()=>({ok:true,json:async()=>({hash:hash('a'),block:hash('b'),valid_contract:false})});
    assert.equal(await deploy.checkDeploymentTransaction('preview',hash('a')),'INVALID');
    global.fetch=async()=>({ok:true,json:async()=>({hash:hash('a'),block:hash('b'),valid_contract:true})});
    assert.equal(await deploy.checkDeploymentTransaction('preview',hash('a')),'CONFIRMED');
    global.fetch=async()=>({ok:true,json:async()=>({hash:hash('c'),block:hash('b'),valid_contract:true})});
    await assert.rejects(deploy.checkDeploymentTransaction('preview',hash('a')),/exact transaction/);
  }finally{global.fetch=originalFetch}
  console.log('PASS exact transaction and successful ledger confirmation checks');
}
main().catch(e=>{console.error(e);process.exitCode=1});

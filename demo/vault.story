story id=vault name="The Vault's Price" start=open
node id=open kind=beat text="The seal cracks. Gold light spills from the vault." image=vault_open.png next=pedestal pos=7,418
node id=pedestal kind=choice text="A crown rests on a pedestal; a corpse clutches a warning." next=leave pos=252,399
choice from=pedestal text="Take the crown" to=worn
choice from=pedestal text="Read the warning first" to=warned
choice from=pedestal text="Leave it and go"
node id=worn kind=beat text="It is warm. Too warm. Your thoughts turn to gold." next=dead pos=469,181
effect from=worn name=give_crown param=1
effect from=worn name=mark_greedy param=0
node id=warned kind=beat text="'The crown drinks the wearer.' You step back." next=leave pos=563,327
node id=dead kind=outcome result=die reason="the crown" pos=715,145
effect from=dead name=kill_player param=0
node id=leave kind=outcome result=survive reason="you kept your head" reward=vault_reward.png pos=753,457
effect from=leave name=resolve_vault param=0
